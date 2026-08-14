package io.github.jtplatform.media.talkback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * 设备标识的合法范围是 1..20 位十进制数字——2019 版协议的手机号占 20 位 BCD，
     * 早先按 12 位判定会把合规设备挡在门外。因此这里只断言真正的边界：
     * 非数字一律拒绝，超过 20 位一律拒绝，13 位是合法的。
     */
    @Test
    void rejectsInvalidDeviceIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                new StreamKey("13800A38000", 2, StreamKind.TALKBACK),
                new byte[0], Jt1078Constants.PT_G711A, 0, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> TalkbackPacketEncoder.encode(
                new StreamKey("123456789012345678901", 2, StreamKind.TALKBACK),
                new byte[0], Jt1078Constants.PT_G711A, 0, CLOCK));
    }

    @Test
    void acceptsThe2019TwentyDigitMobileNumber() {
        ByteBuf encoded = TalkbackPacketEncoder.encode(
                new StreamKey("12345678901234567890", 2, StreamKind.TALKBACK),
                new byte[0], Jt1078Constants.PT_G711A, 0, CLOCK);
        try {
            assertNotNull(encoded);
        } finally {
            encoded.release();
        }
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
