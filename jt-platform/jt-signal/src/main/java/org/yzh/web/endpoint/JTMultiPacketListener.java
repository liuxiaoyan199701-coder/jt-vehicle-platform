package org.yzh.web.endpoint;

import io.github.yezhihao.netmc.session.Session;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yzh.protocol.codec.MultiPacket;
import org.yzh.protocol.codec.MultiPacketListener;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t808.T8003;

public class JTMultiPacketListener extends MultiPacketListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(JTMultiPacketListener.class);

    public JTMultiPacketListener(int timeout) {
        super(timeout);
    }

    @Override
    public boolean receiveTimeout(MultiPacket multiPacket) {
        if (multiPacket.getRetryCount() > 5) {
            return false;
        }

        T8003 request = new T8003();
        request.setMessageId(JT808.服务器补传分包请求);
        request.copyBy(multiPacket.getFirstPacket());
        request.setResponseSerialNo(multiPacket.getSerialNo());
        List<Integer> notArrived = multiPacket.getNotArrived();
        short[] packetIds = new short[notArrived.size()];
        for (int index = 0; index < packetIds.length; index++) {
            packetIds[index] = notArrived.get(index).shortValue();
        }
        request.setId(packetIds);

        Session session = multiPacket.getFirstPacket().getSession();
        if (session == null) {
            return false;
        }
        multiPacket.addRetryCount(1);
        session.notify(request).subscribe(
                ignored -> { },
                error -> LOGGER.warn("Failed to request retransmission from {}", session, error));
        return true;
    }
}
