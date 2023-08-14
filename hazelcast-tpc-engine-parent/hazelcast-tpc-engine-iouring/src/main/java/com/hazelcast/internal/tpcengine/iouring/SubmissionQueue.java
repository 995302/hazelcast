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

package com.hazelcast.internal.tpcengine.iouring;

import com.hazelcast.internal.tpcengine.util.UnsafeLocator;
import sun.misc.Unsafe;

import java.io.UncheckedIOException;

import static com.hazelcast.internal.tpcengine.iouring.Linux.errorcode;
import static com.hazelcast.internal.tpcengine.iouring.Linux.strerror;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_ENTER_GETEVENTS;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_ENTER_REGISTERED_RING;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_OP_NOP;
import static com.hazelcast.internal.tpcengine.util.BitUtil.SIZEOF_INT;
import static com.hazelcast.internal.tpcengine.util.ExceptionUtil.newUncheckedIOException;

/**
 * Represent the Uring ringbuffer for submission queue events (sqe's).
 */
// https://github.com/axboe/liburing/blob/master/src/include/liburing.h
@SuppressWarnings({"checkstyle:ConstantName",
        "checkstyle:ParameterName",
        "checkstyle:MethodName",
        "checkstyle:ParameterNumber"})
public final class SubmissionQueue {
    public static final int OFFSET_SQE_opcode = 0;
    public static final int OFFSET_SQE_flags = 1;
    public static final int OFFSET_SQE_ioprio = 2;
    public static final int OFFSET_SQE_fd = 4;
    public static final int OFFSET_SQE_off = 8;
    public static final int OFFSET_SQE_addr = 16;
    public static final int OFFSET_SQE_len = 24;
    public static final int OFFSET_SQE_rw_flags = 28;
    public static final int OFFSET_SQE_user_data = 32;
    public static final int SIZEOF_SQE = 64;

    private static final Unsafe UNSAFE = UnsafeLocator.UNSAFE;

    boolean ringBufferRegistered;
    int enterRingFd;
    int ringFd;

    long headAddr;
    long tailAddr;
    long sqesAddr;
    long arrayAddr;
    int ringMask;
    int ringEntries;
    int localTail;
    int localHead;
    private final Uring uring;

    SubmissionQueue(Uring uring) {
        this.uring = uring;
        this.ringFd = uring.ringFd;
        this.enterRingFd = uring.enterRingFd;
    }

    public Uring uring() {
        return uring;
    }

    void init(long io_uring) {
        init0(io_uring);
        //UNSAFE.setMemory(sqesAddr, SIZE_SQE * (long)ringEntries, (byte) 0);
        localHead = acquireHead();
        localTail = acquireTail();
    }

    private native void init0(long io_uring);

    public int acquireHead() {
        return UNSAFE.getIntVolatile(null, headAddr);
    }

    public void releaseHead(int newHead) {
        UNSAFE.putOrderedInt(null, headAddr, newHead);
    }

    public int tail() {
        return UNSAFE.getInt(null, tailAddr);
    }

    public void tail(int tail) {
        UNSAFE.putInt(null, tailAddr, tail);
    }

    public int acquireTail() {
        return UNSAFE.getIntVolatile(null, tailAddr);
    }

    public void releaseTail(int tail) {
        UNSAFE.putOrderedInt(null, this.tailAddr, tail);
    }

    public void array(int pos, int value) {
        UNSAFE.putInt(arrayAddr + SIZEOF_INT * pos, value);
    }

    /**
     * Gets the free index in the submission queue.
     *
     * @return the next free index or -1 if there is no space.
     */
    public int nextIndex() {
        int size = localTail - localHead;
        if (size == ringEntries) {
            // there is no more space; but this is based on the cached head
            // lets update the localHead and try again.
            long oldLocalHead = localHead;
            // refresh the local head because it could be stale.
            localHead = acquireHead();
            if (oldLocalHead == localHead) {
                // there really is no space.
                return -1;
            }
        }

        int tail = localTail;
        //System.out.println("SubmissionQueue nextSqIndex:" + (tail & ringMask));
        localTail++;
        return tail & ringMask;
    }

    public void writeSqe(int index,
                         byte opcode,
                         int flags,
                         int rwFlags,
                         int fd,
                         long bufferAddr,
                         int length,
                         long offset,
                         long userData) {
        //System.out.println("writeSqe:" + index);
        long sqeAddr = sqesAddr + index * SIZEOF_SQE;
        UNSAFE.putByte(sqeAddr + OFFSET_SQE_opcode, opcode);
        UNSAFE.putByte(sqeAddr + OFFSET_SQE_flags, (byte) flags);
        UNSAFE.putShort(sqeAddr + OFFSET_SQE_ioprio, (short) 0);
        UNSAFE.putInt(sqeAddr + OFFSET_SQE_fd, fd);
        UNSAFE.putLong(sqeAddr + OFFSET_SQE_off, offset);
        UNSAFE.putLong(sqeAddr + OFFSET_SQE_addr, bufferAddr);
        UNSAFE.putInt(sqeAddr + OFFSET_SQE_len, length);
        UNSAFE.putInt(sqeAddr + OFFSET_SQE_rw_flags, rwFlags);
        UNSAFE.putLong(sqeAddr + OFFSET_SQE_user_data, userData);
        //System.out.println("SubmissionQueue: userdata:" + userData + " index:" + index + " op:" + opcode);
    }

    /**
     * Offers a sqe to the SubmissionQueue.
     *
     * @param opcode
     * @param flags
     * @param rwFlags
     * @param fd
     * @param bufferAddress
     * @param length
     * @param offset
     * @param userdata
     * @return true if successfully offered, false if there was no space.
     */
    public boolean offer(byte opcode,
                         int flags,
                         int rwFlags,
                         int fd,
                         long bufferAddress,
                         int length,
                         long offset,
                         long userdata) {
        int index = nextIndex();
        if (index == -1) {
            return false;
        }
        writeSqe(index, opcode, flags, rwFlags, fd, bufferAddress, length, offset, userdata);
        return true;
    }

    /**
     * Adds a sqe to the SubmissionQueue.
     *
     * @param opcode
     * @param flags
     * @param rwFlags
     * @param fd
     * @param bufferAddress
     * @param length
     * @param offset
     * @param userdata
     * @throws IllegalStateException if there is no space.
     */
    public void add(byte opcode,
                    int flags,
                    int rwFlags,
                    int fd,
                    long bufferAddress,
                    int length,
                    long offset,
                    long userdata) {
        int index = nextIndex();
        if (index == -1) {
            throw new IllegalStateException("No space in the submission queue");
        }
        writeSqe(index, opcode, flags, rwFlags, fd, bufferAddress, length, offset, userdata);
    }

    public boolean offer_NOP(long userdata) {
        int index = nextIndex();
        if (index == -1) {
            return false;
        } else {
            writeSqe(index, IORING_OP_NOP, 0, 0, 0, 0, 0, 0, userdata);
            return true;
        }
    }

    /**
     * Submits and
     * <p>
     * https://manpages.debian.org/unstable/liburing-dev/io_uring_enter.2.en.html
     *
     * @return the number of submitted entries.
     */
    public int enter(int toSubmit, int minComplete, int flags) {
        if (ringBufferRegistered) {
            flags |= IORING_ENTER_REGISTERED_RING;
        }

        return Uring.enter(enterRingFd, toSubmit, minComplete, flags);
    }

    /**
     * Submits pending items and then waits for at least 1 completed event.
     *
     * @return the number of submitted items.
     */
    public int submitAndWait() {
        int tail = UNSAFE.getIntVolatile(null, tailAddr);
        int toSubmit = localTail - tail;
        UNSAFE.putOrderedInt(null, this.tailAddr, localTail);

        int flags = IORING_ENTER_GETEVENTS;
        if (ringBufferRegistered) {
            flags |= IORING_ENTER_REGISTERED_RING;
        }

        int res = Uring.enter(enterRingFd, toSubmit, 1, flags);

        if (res >= 0) {
            return toSubmit;
        } else {
            throw newEnterFailedException(-res);
        }
    }

    public int submit() {
        // acquire load.
        int tail = UNSAFE.getIntVolatile(null, tailAddr);
        int toSubmit = localTail - tail;

        //System.out.println("Sq::submit toSubmit "+toSubmit +" localTail:"+localTail+" tail:"+tail);

        if (toSubmit == 0) {
            return 0;
        }
        int flags = 0;
        if (ringBufferRegistered) {
            flags |= IORING_ENTER_REGISTERED_RING;
        }

        // release store.
        UNSAFE.putOrderedInt(null, this.tailAddr, localTail);

        int res = Uring.enter(enterRingFd, toSubmit, 0, flags);
        if (res < 0) {
            throw newEnterFailedException(-res);
        }
        // System.out.println("Sq::submit res "+res);

        if (res != toSubmit) {
            System.out.println("Not all items got submitted");
        }
        return toSubmit;
    }

    void onClose() {
        ringFd = -1;
        headAddr = 0;
        tailAddr = 0;
        sqesAddr = 0;
        arrayAddr = 0;
        ringMask = 0;
        ringEntries = 0;
    }

    private static UncheckedIOException newEnterFailedException(int errnum) {
        return newUncheckedIOException("Failed to submit work to io_uring. "
                + "io_uring_enter failed with error " + errorcode(errnum) + " '" + strerror(errnum) + "'."
                + "Go to https://man7.org/linux/man-pages/man2/io_uring_enter.2.html section 'ERRORS',"
                + "for a proper explanation of the errorcode.");
    }
}
