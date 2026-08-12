package org.yzh.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.yzh.protocol.basics.JTMessage;

class MultiPacketTest {
    @Test
    void capturesTheFirstPacketSerialNumberForRetransmissionRequests() {
        JTMessage firstPacket = new JTMessage();
        firstPacket.setSubpackage(true);
        firstPacket.setPackageTotal(2);
        MultiPacket packets = new MultiPacket(firstPacket);

        packets.setSerialNo(37);
        packets.setSerialNo(38);

        assertEquals(37, packets.getSerialNo());
    }
}
