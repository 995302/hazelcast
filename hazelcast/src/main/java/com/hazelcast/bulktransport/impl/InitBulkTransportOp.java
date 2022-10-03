package com.hazelcast.bulktransport.impl;

import com.hazelcast.tpc.offheapmap.OffheapMap;
import com.hazelcast.tpc.requestservice.FrameCodec;
import com.hazelcast.tpc.requestservice.Op;
import com.hazelcast.tpc.requestservice.OpCodes;
import com.hazelcast.table.impl.TableManager;

import static com.hazelcast.tpc.requestservice.FrameCodec.OFFSET_REQ_CALL_ID;

public class InitBulkTransportOp extends Op {

    public InitBulkTransportOp() {
        super(OpCodes.INIT_BULK_TRANSPORT);
    }

    @Override
    public int run() throws Exception {
        TableManager tableManager = managers.tableManager;
        OffheapMap map = tableManager.getOffheapMap(partitionId, null);

        FrameCodec.writeResponseHeader(response, partitionId, request.getLong(OFFSET_REQ_CALL_ID));
        FrameCodec.constructComplete(response);
        return COMPLETED;
    }
}
