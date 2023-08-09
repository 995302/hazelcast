/*
 * Copyright (c) 2008-2023, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.tpcengine.net;

import com.hazelcast.internal.tpcengine.Eventloop;
import com.hazelcast.internal.tpcengine.Option;
import com.hazelcast.internal.tpcengine.Reactor;
import com.hazelcast.internal.tpcengine.iobuffer.IOBuffer;
import com.hazelcast.internal.tpcengine.util.AbstractBuilder;
import org.jctools.queues.MpmcArrayQueue;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static com.hazelcast.internal.tpcengine.util.Preconditions.checkNotNull;
import static com.hazelcast.internal.tpcengine.util.Preconditions.checkPositive;
import static java.lang.Thread.currentThread;

/**
 * A Socket that is asynchronous. So reads and writes do not block,
 * but are executed on an {@link Reactor}.
 * <p/>
 * If in the future we want to support Virtual Threads, we do not need to
 * introduce a new 'SyncSocket'. It would be sufficient to rename this class
 * to e.g. Socket and offer a blocking read.
 */
@SuppressWarnings({"checkstyle:MethodCount", "checkstyle:VisibilityModifier"})
public abstract class AsyncSocket extends AbstractAsyncSocket {

    protected static final VarHandle LAST_READ_TIME_NANOS;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            LAST_READ_TIME_NANOS = l.findVarHandle(AsyncSocket.class, "lastReadTimeNanos", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final AtomicReference<Thread> flushThread = new AtomicReference<>(currentThread());
    protected final Queue writeQueue;
    protected final Thread eventloopThread;
    protected final Metrics metrics = new Metrics();
    protected final boolean clientSide;
    protected final Reactor reactor;
    protected final NetworkScheduler networkScheduler;
    protected final Reader reader;
    protected final Options options;
    protected final Eventloop eventloop;
    protected volatile long lastReadTimeNanos = -1;
    protected volatile SocketAddress remoteAddress;
    protected volatile SocketAddress localAddress;
    // only accessed from eventloop thread.
    protected boolean started;

    protected AsyncSocket(Builder builder) {
        this.clientSide = builder.clientSide;
        this.reactor = builder.reactor;
        this.eventloop = builder.reactor.eventloop();
        this.eventloopThread = reactor.eventloopThread();
        this.networkScheduler = builder.networkScheduler;
        this.writeQueue = builder.writeQueue;
        this.reader = builder.reader;
        this.options = builder.options;
        reader.init(this);
    }

    /**
     * Return the {@link Metrics} of this AsyncSocket.
     * <p/>
     * This call can always be made no matter the state of the socket.
     *
     * @return the metrics.
     */
    public final Metrics metrics() {
        return metrics;
    }

    /**
     * Gets the {@link Reactor} this {@link AsyncSocket} belongs to.
     *
     * @return the {@link Reactor} this AsyncSocket belongs.
     */
    public final Reactor reactor() {
        return reactor;
    }

    /**
     * Returns the {@link Options} of this AsyncSocket.
     *
     * @return the AsyncSocketOptions.
     */
    public final Options options() {
        return options;
    }

    /**
     * Gets the remote address.
     * <p>
     * If the AsyncSocket isn't connected yet, null is returned.
     * <p>
     * This method is thread-safe.
     *
     * @return the remote address.
     */
    public final SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Gets the local address.
     * <p>
     * If the AsyncSocket isn't connected yet, null is returned.
     * <p>
     * This method is thread-safe.
     *
     * @return the local address.
     */
    public final SocketAddress getLocalAddress() {
        return localAddress;
    }

    /**
     * Returns the last time in nanoseconds after the epoch this socket has
     * read something from the network.
     * <p/>
     * This method is thread-safe.
     *
     * @return the last time in nanoseconds after the epoch this socket has
     * read something. If nothing has been read before, -1 is returned.
     */
    public final long lastReadTimeNanos() {
        return (long) LAST_READ_TIME_NANOS.getOpaque(this);
    }

    /**
     * Configures if this AsyncSocket is readable or not. If there is no change
     * in the readable status, the call is ignored.
     * <p/>
     * When an AsyncSocket is readable, it will schedule itself at the Reactor
     * as soon as data is received at the receive buffer of the socket, so that
     * the received data gets processed. When it isn't readable, data might be
     * received at the receive buffer, but the socket will not schedule itself.
     * <p/>
     * This functionality can be used to apply backpressure. So what happens is
     * that the receive buffer fills up. Once it fills up and the other side keeps
     * sending data, the remote send buffer fills up as well and the pressure get
     * propagated upstream.
     * <p/>
     * This call can safely be made from any thread, but typically you want to
     * call it from the eventloop-thread. This call is blocking; this isn't an
     * issue for the eventloop thread because it is an instantaneous call. For
     * any other thread this call is not cheap.
     *
     * @param readable the new readable status.
     * @throws RuntimeException if the readable status could not be set.
     */
    public abstract void setReadable(boolean readable);

    /**
     * Checks if this AsyncSocket is readable. For more information see
     * {@link #setReadable(boolean)}.
     * <p/>
     * This call can safely be made from any thread, but typically you want to
     * call it from the eventloop-thread. This call is blocking; this isn't an
     * issue for the eventloop thread because it is an instantaneous call. For
     * any other thread this call is not cheap.
     *
     * @return true if readable, false otherwise.
     * @throws RuntimeException if the readable status could not be retrieved.
     */
    public abstract boolean isReadable();

    /**
     * Start the AsyncSocket by scheduling it on the reactor. The Socket should
     * be started only once.
     * <p/>
     * Typically you do not want to share this AsyncSocket with other threads
     * till this method is called.
     *
     * @throws RuntimeException if the Socket could not be started.
     */
    public final void start() {
        if (Thread.currentThread() == eventloopThread) {
            startInternal();
        } else {
            reactor.submit(this::startInternal).join();
        }
    }

    private void startInternal() {
        if (started) {
            throw new IllegalStateException(this + " is already started");
        }
        started = true;
        start0();
    }

    protected abstract void start0();


    /**
     * Ensures that any scheduled IOBuffers are flushed to the socket at some point
     * in the future.
     * <p>
     * What happens under the hood is that the AsyncSocket is scheduled in the
     * {@link Reactor} where at some point in the future the IOBuffers get written
     * to the socket.
     * <p>
     * This method is thread-safe.
     * <p>
     * This call is ignored when then AsyncSocket is already closed.
     */
    public final void flush() {
        Thread currentThread = currentThread();

        if (flushThread.get() != null) {
            // the socket is already flushed, we are done.
            return;
        }

        // The socket is not flushed, so we are going to try to flush it.
        if (!flushThread.compareAndSet(null, currentThread)) {
            // A different thread triggered a flush, we are done.
            return;
        }

        // We successfully managed to flush this socket, now we need to
        // schedule it at the network scheduler so it gets picked up for
        // processing.
        if (currentThread == eventloopThread) {
            // todo: return value
            networkScheduler.unsafeSchedule(this);
        } else {
            // todo: return value
            networkScheduler.schedule(this);
            reactor.wakeup();
        }
    }

    protected final void resetFlushed() {
        // todo: we only need to call reset flushed if the writeQueue is drained

        flushThread.set(null);

        if (writeQueue.isEmpty()) {
            // The socket is clear, so we are done.
            return;
        }

        // Darn, even though we successfully managed to unflush, the socket is
        // dirty So we need to flush it to prevent ending up with dirty socket
        // that isn't flushed.
        flush();
    }


    /**
     * Writes a {@link IOBuffer} to this AsyncSocket without flushing (scheduling)
     * the AsyncSocket.
     * <p>
     * This call can be used to buffer a series of IOBuffers and then call
     * {@link #flush()} to trigger the actual writing to the socket.
     * <p>
     * There is no guarantee that IOBuffer is actually going to be received by
     * the caller after the AsyncSocket has accepted the IOBuffer. E.g. when
     * the TCP/IP connection is dropped.
     * <p>
     * This method is thread-safe.
     *
     * @param buf the IOBuffer to write.
     * @return true if the IOBuffer was accepted, false otherwise.
     */
    public final boolean write(Object buf) {
        if (writeQueue.add(buf)) {
            return true;
        } else {
            // lets trigger a flush since the writeQueue is full.
            flush();
            return false;
        }
    }

    /**
     * Writes a {@link IOBuffer} to this AsyncSocket and flushes it. Flushing
     * causes the AsyncSocket
     * to be scheduled in the {@link Reactor}.
     * <p>
     * This is the same as calling {@link #write(Object)} followed by a
     * {@link #flush()}.
     * <p>
     * There is no guarantee that IOBuffer is actually going to be received by
     * the caller if the AsyncSocket has accepted the IOBuffer. E.g. when the
     * connection closes.
     * <p>
     * This method is thread-safe.
     *
     * @param buf the IOBuffer to write.
     * @return true if the IOBuffer was accepted, false otherwise.
     */
    public final boolean writeAndFlush(Object buf) {
        boolean offered = write(buf);
        flush();
        return offered;
    }

    /**
     * Writes an {@link IOBuffer} and ensure it gets written from the eventloop
     * thread.
     * <p>
     * This call can only be made inside the eventloop.
     *
     * @return true if the buf was successfully offered, false otherwise.
     * @throws IllegalStateException if the current thread isn't the eventloop
     *                               thread.
     */
    public final boolean insideWriteAndFlush(Object buf) {
        Thread currentThread = currentThread();

        if (currentThread != eventloopThread) {
            throw new IllegalStateException(
                    "unsafeWriteAndFlush can only be made from eventloop thread, "
                            + "found " + currentThread);
        }

        boolean triggeredFlush;

        Thread currentFlushThread = flushThread.get();
        if (currentFlushThread == null) {
            // the socket isn't flushed, lets try to flush it.
            triggeredFlush = flushThread.compareAndSet(null, currentThread);
            // At this point we know for sure that the socket was flushed; either
            // by the current thread or by a different one.
        } else {
            // the socket was already flushed
            triggeredFlush = false;
        }

        boolean offered = insideWrite(buf);

        if (triggeredFlush && offered) {
            // We only want to schedule the socket if the buf was successfully
            // offered and we triggered the flush. Since we are on the eventloop
            // thread, unsafeSchedule can be called.
            networkScheduler.unsafeSchedule(this);
        }

        return offered;
    }

    protected abstract boolean insideWrite(Object buf);

    /**
     * Connects asynchronously to some address.
     * <p/>
     * This method is not thread-safe.
     * <p/>
     * This method should be called after {@link #start()}.
     * <p>
     * todo: Instead of returning a CompletableFuture, a promise should be returned.
     *
     * @param address the address to connect to.
     * @return a {@link CompletableFuture}
     * @throws NullPointerException if address is null
     */
    public abstract CompletableFuture<Void> connect(SocketAddress address);

    @Override
    protected void close0() throws IOException {
        reactor.sockets().remove(this);
        localAddress = null;
        remoteAddress = null;
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + "[" + localAddress + "->" + remoteAddress + "]";
    }

    // Do not remove this code. This exists for debugging purposes so it is easy to
    // distinguish the client from the server side communication.
//    @Override
//    public final String toString() {
//        if (clientSide) {
//            return getClass().getSimpleName() + "[" + localAddress + "->" + remoteAddress + "]";
//        } else {
//            return "            " + getClass().getSimpleName() + "[" + localAddress + "->" + remoteAddress + "]";
//        }
//    }


    /**
     * Contains the metrics for an {@link AsyncSocket}.
     * <p/>
     * The metrics should only be updated by the event loop thread, but can be read
     * by any thread.
     */
    @SuppressWarnings("checkstyle:ConstantName")
    public static final class Metrics {

        private static final VarHandle BYTES_READ;
        private static final VarHandle BYTES_WRITTEN;
        private static final VarHandle WRITES;
        private static final VarHandle READS;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                BYTES_READ = l.findVarHandle(Metrics.class, "bytesRead", long.class);
                BYTES_WRITTEN = l.findVarHandle(Metrics.class, "bytesWritten", long.class);
                WRITES = l.findVarHandle(Metrics.class, "writes", long.class);
                READS = l.findVarHandle(Metrics.class, "reads", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private volatile long bytesRead;
        private volatile long bytesWritten;
        private volatile long writes;
        private volatile long reads;

        /**
         * Returns bytes read.
         *
         * @return bytes read.
         */
        public long bytesRead() {
            return (long) BYTES_READ.getOpaque(this);
        }

        /**
         * Increases the bytes read.
         *
         * @param delta the amount to increase.
         */
        public void incBytesRead(long delta) {
            BYTES_READ.setOpaque(this, (long) BYTES_READ.getOpaque(this) + delta);
        }

        /**
         * Returns the bytes written.
         *
         * @return the bytes written.
         */
        public long bytesWritten() {
            return (long) BYTES_WRITTEN.getOpaque(this);
        }

        /**
         * Increases the bytes written.
         *
         * @param delta the amount to increase.
         */
        public void incBytesWritten(long delta) {
            BYTES_WRITTEN.setOpaque(this, (long) BYTES_WRITTEN.getOpaque(this) + delta);
        }

        /**
         * Returns the number of write events. So the number of times the
         * {@link AsyncSocket} was scheduled on the {@link Reactor} for
         * writing purposes.
         *
         * @return number of write events.
         */
        public long writes() {
            return (long) WRITES.getOpaque(this);
        }

        /**
         * Increases the number of write events by 1.
         */
        public void incWrites() {
            WRITES.setOpaque(this, (long) WRITES.getOpaque(this) + 1);
        }

        /**
         * Returns the number of read events. So the number of times the
         * {@link AsyncSocket} was scheduled on the {@link Reactor} for
         * reading purposes.
         *
         * @return number of read events.
         */
        public long reads() {
            return (long) READS.getOpaque(this);
        }

        /**
         * Increases the number of read events by 1.
         */
        public void incReads() {
            READS.setOpaque(this, (long) READS.getOpaque(this) + 1);
        }
    }

    /**
     * Options for the {@link AsyncSocket} and {@link AsyncServerSocket}.
     * <p>
     * Reason for the name: there already exists class with these names:
     * - com.hazelcast.client.config.SocketOptions
     * - java.net.SocketOptions.
     */
    public interface Options {

        /**
         * See {@link java.net.SocketOptions#SO_RCVBUF}.
         */
        Option<Integer> SO_RCVBUF = new Option<>("SO_RCVBUF", Integer.class);

        /**
         * See {@link java.net.SocketOptions#SO_SNDBUF}
         */
        Option<Integer> SO_SNDBUF = new Option<>("SO_SNDBUF", Integer.class);

        /**
         * See {@link java.net.SocketOptions#SO_KEEPALIVE}
         */
        Option<Boolean> SO_KEEPALIVE = new Option<>("SO_KEEPALIVE", Boolean.class);

        /**
         * See {@link java.net.SocketOptions#SO_REUSEPORT}
         */
        Option<Boolean> SO_REUSEPORT = new Option<>("SO_REUSEPORT", Boolean.class);

        /**
         * See {@link java.net.SocketOptions#SO_REUSEADDR}
         */
        Option<Boolean> SO_REUSEADDR = new Option<>("SO_REUSEADDR", Boolean.class);

        /**
         * See {@link java.net.SocketOptions#TCP_NODELAY}
         */
        Option<Boolean> TCP_NODELAY = new Option<>("TCP_NODELAY", Boolean.class);

        /**
         * See {@code jdk.net.ExtendedSocketOptions#TCP_KEEPIDLE}
         */
        Option<Integer> TCP_KEEPIDLE = new Option<>("TCP_KEEPIDLE", Integer.class);

        /**
         * See {@code jdk.net.ExtendedSocketOptions#TCP_KEEPINTERVAL}
         */
        Option<Integer> TCP_KEEPINTERVAL = new Option<>("TCP_KEEPINTERVAL", Integer.class);

        /**
         * See {@code jdk.net.ExtendedSocketOptions#TCP_KEEPCOUNT}
         */
        Option<Integer> TCP_KEEPCOUNT = new Option<>("TCP_KEEPCOUNT", Integer.class);


        /**
         * Checks if the option is supported.
         *
         * @param option the option
         * @return true if supported, false otherwise
         * @throws NullPointerException if option is <code>null</code>.
         */
        boolean isSupported(Option option);

        /**
         * Sets an option value if that option is supported.
         *
         * @param option the option
         * @param value  the value
         * @param <T>    the type of the value
         * @return <code>true</code> if the option was supported,
         * <code>false</code> otherwise.
         * @throws NullPointerException         if option or value is null.
         * @throws java.io.UncheckedIOException if the value could not be set.
         */

        <T> boolean set(Option<T> option, T value);

        /**
         * Gets an option value. If option was not set or is not supported,
         * <code>null</code> is returned.
         *
         * @param option the option
         * @param <T>    the type of the value
         * @return the value for the option, <code>null</code> if the option
         * was not set or is not supported.
         * @throws java.io.UncheckedIOException if the value could not be gotten.
         */
        <T> T get(Option<T> option);
    }


    /**
     * The {@link Reader} is called when data is received on an
     * {@link AsyncSocket} and needs to be processed by the application. This
     * is where e.g. ClientMessages of Packets could be created.
     */
    public abstract static class Reader {

        protected AsyncSocket socket;
        protected Reactor reactor;
        protected Eventloop eventloop;

        /**
         * Initializes the Reader. This method is called once and from the
         * eventloop thread that owns the socket.
         *
         * @param socket the socket this Reader belongs to.
         */
        public void init(AsyncSocket socket) {
            this.socket = checkNotNull(socket);
            this.reactor = socket.reactor();
            this.eventloop = reactor.eventloop();
        }

        /**
         * Process the received data on the socket.
         * <p/>
         * Idea:
         * Currently we are forced to consume the bytes from the src buffer; so
         * we need to copy them into a different structure (e.g. an IOBuffer)
         * because the src buffer will be used for the next read from the socket.
         * This can be solved by letting this method return the next 'src' buffer;
         * so the buffer where the data from the socket is going to end up in.
         * This way the current 'src' buffer can be used for processing, while
         * the returned src buffer can be used to read the new data from the
         * socket from.
         *
         * @param src the ByteBuffer containing the received data.
         */
        public abstract void onRead(ByteBuffer src);
    }

    /**
     * A {@link AsyncSocket} Builder.
     * <p/>
     * This builder assumes TCP/IPv4. For different types of sockets
     * new configuration options on this builder need to be added or
     * {@link Reactor#newAsyncSocketBuilder()} needs to be modified.
     * <p/>
     * Cast to specific builder for specialized options when available.
     */
    @SuppressWarnings({"checkstyle:VisibilityModifier"})
    public abstract static class Builder extends AbstractBuilder<AsyncSocket> {
        static final int DEFAULT_WRITE_QUEUE_CAPACITY = 2 << 16;

        public AcceptRequest acceptRequest;
        public Reactor reactor;
        public NetworkScheduler networkScheduler;
        public int writeQueueCapacity = DEFAULT_WRITE_QUEUE_CAPACITY;
        public Reader reader;
        public boolean clientSide;
        public Queue writeQueue;
        public Options options;

        @Override
        protected void conclude() {
            super.conclude();

            checkNotNull(reactor, "reactor");
            checkNotNull(networkScheduler, "networkScheduler");
            checkPositive(writeQueueCapacity, "writeQueueCapacity");
            checkNotNull(reader, "reader");
            checkNotNull(options, "options");

            if (writeQueue == null) {
                writeQueue = new MpmcArrayQueue(writeQueueCapacity);
            }
        }
    }
}
