package org.yzh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.yezhihao.protostar.util.Explain;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t808.T0200;

class ElucidatorRegressionTest {
    private static final String LOCATION_SAMPLE = "020000d40123456789017fff000004000000080006eeb6ad02633df7"
            + "013800030063200707192359642f000000400101020a0a02010a1e00640001b2070003640e200707192359"
            + "000100000061646173200827111111010101652f000000410202020a0000000a1e00c8000516150006c81c"
            + "20070719235900020000000064736d200827111111020202662900000042031e012c00087a23000a2c2a20"
            + "0707192359000300000074706d732008271111110303030067290000004304041e0190000bde31000d903820"
            + "07071923590004000000006273642008271111110404049d";
    private static final String UPSTREAM_NORMALIZED_SAMPLE = "7e020000d40123456789017fff000004000000080006eeb6"
            + "ad02633df7013800030063200707192359642f000000400101020a0a02010a1e00640001b2070003640e2007"
            + "07192359000161646173000000200827111111010101652f000000410202020a0000000a1e00c80005161500"
            + "06c81c200707192359000264736d00000000200827111111020202662900000042031e012c00087a23000a2c"
            + "2a200707192359000374706d730000002008271111110303030067290000004304041e0190000bde31000d90"
            + "382007071923590004627364000000002008271111110404049d7e";

    @Test
    void decodesAndReencodesTheElucidatorSampleByteForByte() {
        ByteBuf source = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(LOCATION_SAMPLE));
        ByteBuf encoded = null;
        try {
            JTMessage decoded = Elucidator.coder.decode(source, new Explain());

            T0200 location = assertInstanceOf(T0200.class, decoded);
            assertEquals(0x0200, location.getMessageId());
            encoded = Elucidator.coder.encode(location, new Explain());
            assertEquals(UPSTREAM_NORMALIZED_SAMPLE, ByteBufUtil.hexDump(encoded));
        } finally {
            source.release();
            if (encoded != null) {
                encoded.release();
            }
        }
    }
}
