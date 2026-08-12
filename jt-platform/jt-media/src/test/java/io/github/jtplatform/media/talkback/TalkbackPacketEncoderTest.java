package io.github.jtplatform.media.talkback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TalkbackPacketEncoderTest {
    private static final StreamKey STREAM = new StreamKey("13800138000", 2, StreamKind.TALKBACK);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-10T12:34:56.789Z"), ZoneOffset.UTC);

    @Test
    void encodesAStandardAtomicG711APacket() {
        byte[] audio = {(byte) 0xd5, (byte) 0xc7, (byte) 0xfa};

        ByteBuf encoded = TalkbackPacketEncoder.encode(
                STREAM, audio, Jt1078Constants.PT_G711A, 0xabcd, CLOCK);
        try {
            assertEquals(
                    "303163648186abcd01380013800002300000019febab40950003d5c7fa",
                    ByteBufUtil.hexDump(encoded));
        } finally {
            encoded.release();
        }
    }

    @Test
    void rejectsInvalidDeviceIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                new StreamKey("13800A38000", 2, StreamKind.TALKBACK),
                new byte[0], Jt1078Constants.PT_G711A, 0, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                new StreamKey("1234567890123", 2, StreamKind.TALKBACK),
                new byte[0], Jt1078Constants.PT_G711A, 0, CLOCK));
    }

    @Test
    void rejectsValuesThatDoNotFitProtocolFields() {
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                STREAM, new byte[0], -1, 0, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                STREAM, new byte[0], 128, 0, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                STREAM, new byte[0], Jt1078Constants.PT_G711A, -1, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                STREAM, new byte[0], Jt1078Constants.PT_G711A, 65_536, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                STREAM, new byte[65_536], Jt1078Constants.PT_G711A, 0, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                STREAM,
                new byte[0],
                Jt1078Constants.PT_G711A,
                0,
                Clock.fixed(Instant.ofEpochMilli(-1), ZoneOffset.UTC)));
    }
}
