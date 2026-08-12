package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;

class SignalCodecTest {
    @Test
    void serialNumberUsesTheUnsignedNonZeroRange() {
        SerialNumber serial = new SerialNumber(65_534);

        assertEquals(65_535, serial.next());
        assertEquals(1, serial.next());
        assertEquals(2, serial.next());
    }

    @Test
    void frameReaderSkipsNoiseAndEmptyDelimiters() throws Exception {
        byte[] input = {1, 2, 0x7e, 0x7e, 0x01, 0x02, 0x7e};

        assertArrayEquals(new byte[] {0x7e, 0x01, 0x02, 0x7e},
                new Jt808FrameReader(new ByteArrayInputStream(input)).readFrame());
    }

    @Test
    void frameReaderRejectsAnUnboundedFrame() {
        byte[] input = {0x7e, 1, 2, 3, 4, 5, 0x7e};
        Jt808FrameReader reader = new Jt808FrameReader(new ByteArrayInputStream(input), 5);

        assertThrows(IOException.class, reader::readFrame);
    }

    @Test
    void writerSerializesACompleteEncodedFrame() throws Exception {
        Jt808MessageCodec codec = new Jt808MessageCodec();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JTMessage heartbeat = new JTMessage();
        heartbeat.setMessageId(JT808.终端心跳);
        heartbeat.setClientId("123456789012");
        heartbeat.setSerialNo(9);

        new Jt808MessageWriter(output, codec).write(heartbeat);

        JTMessage decoded = codec.decode(output.toByteArray());
        assertEquals(JT808.终端心跳, decoded.getMessageId());
        assertEquals(9, decoded.getSerialNo());
        assertEquals("123456789012", decoded.getClientId());
        assertEquals(true, decoded.isVerified());
    }
}
