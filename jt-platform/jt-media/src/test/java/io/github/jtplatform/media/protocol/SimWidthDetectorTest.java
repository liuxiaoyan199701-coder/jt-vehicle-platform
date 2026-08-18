package io.github.jtplatform.media.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * SIM 字段宽度的判定。
 *
 * <p><b>为什么这组断言是本次改动的核心</b>：判错的表现不是报错，而是「流连上了但画面是花的」
 * ——通道号和帧类型读的是相邻字段拼出来的随机数，解码器一路不抛异常。
 * 这类故障在生产上极难定位，只有断言拦得住。
 */
class SimWidthDetectorTest {

    /** 真实的 11 位号码 13800138000。填进 12 位 BCD 补 1 个前导零。 */
    private static final byte[] SIM_12 = {0x01, 0x38, 0x00, 0x13, (byte) 0x80, 0x00};
    /** 同一个号码填进 20 位 BCD：补 9 个前导零，**前 4 字节必然全 0**。 */
    private static final byte[] SIM_20 = {
        0x00, 0x00, 0x00, 0x00, 0x01, 0x38, 0x00, 0x13, (byte) 0x80, 0x00
    };

    private static ByteBuf packet(int rtpByte, byte[] sim, int dataType, byte[] payload) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeBytes(Jt1078Constants.MAGIC);
        buffer.writeByte(rtpByte);
        buffer.writeByte(0x80 | 98);
        buffer.writeShort(7);
        buffer.writeBytes(sim);
        buffer.writeByte(2);
        buffer.writeByte(dataType << 4);
        if (dataType != Jt1078Constants.TRANSPARENT_DATA) {
            buffer.writeLong(123_456_789L);
        }
        if (dataType <= Jt1078Constants.VIDEO_B_FRAME) {
            buffer.writeShort(40);
            buffer.writeShort(20);
        }
        buffer.writeShort(payload.length);
        buffer.writeBytes(payload);
        return buffer;
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0x7f);
        }
        return data;
    }

    @Test
    void detectsStandardVideoPacket() {
        ByteBuf input = packet(0x81, SIM_12, Jt1078Constants.VIDEO_I_FRAME, payload(64));

        SimWidthDetector.Outcome outcome = SimWidthDetector.detect(input, input.readerIndex());

        assertEquals(SimWidth.STANDARD, outcome.width());
        assertFalse(outcome.tied(), "标准包不该走兜底分支");
    }

    @Test
    void detectsNonStandardTwentyDigitPacket() {
        // 野生设备的样子：写了 10 字节 SIM，但 X 位仍是 0
        ByteBuf input = packet(0x81, SIM_20, Jt1078Constants.VIDEO_I_FRAME, payload(64));

        SimWidthDetector.Outcome outcome = SimWidthDetector.detect(input, input.readerIndex());

        assertEquals(SimWidth.EXTENDED, outcome.width());
        assertFalse(outcome.tied());
    }

    /** X 位已置位时直接采信，不必投票。 */
    @Test
    void trustsTheExtensionBitWithoutVoting() {
        ByteBuf input = packet(0x91, SIM_20, Jt1078Constants.VIDEO_I_FRAME, payload(64));

        SimWidthDetector.Outcome outcome = SimWidthDetector.detect(input, input.readerIndex());

        assertEquals(SimWidth.EXTENDED, outcome.width());
        assertTrue(outcome.reason().contains("X 位"));
    }

    /**
     * 30 字节碰撞：这是整套判定的试金石。
     *
     * <p>{@code 视频帧+BCD[6]} 与 {@code 音频帧+BCD[10]} 的头长都是 30 字节，而且数据体长度
     * 字段落在**同一个偏移**——两种解法读出同一个长度、算出同一个包边界，
     * **边界对齐信号在这里完全失效**。只有前导零模式能把它们分开。
     */
    @Test
    void resolvesTheThirtyByteCollision() {
        ByteBuf video6 = packet(0x81, SIM_12, Jt1078Constants.VIDEO_I_FRAME, payload(32));
        ByteBuf audio10 = packet(0x81, SIM_20, Jt1078Constants.AUDIO_FRAME, payload(32));

        // 先确认碰撞真实存在：两者头长相同，否则这个测试就没有意义了
        assertEquals(
                Jt1078Constants.VIDEO_HEADER_LENGTH,
                Jt1078Constants.AUDIO_HEADER_LENGTH + SimWidth.EXTRA_BYTES,
                "视频+12位 与 音频+20位 的头长必须相同，这正是本用例要覆盖的歧义");

        assertEquals(SimWidth.STANDARD,
                SimWidthDetector.detect(video6, video6.readerIndex()).width());
        assertEquals(SimWidth.EXTENDED,
                SimWidthDetector.detect(audio10, audio10.readerIndex()).width());
    }

    /** 连续两个包时，边界对齐信号能落在下一个魔数上，判定应当更有把握。 */
    @Test
    void usesTheNextPacketMagicAsAnAnchor() {
        ByteBuf first = packet(0x81, SIM_20, Jt1078Constants.VIDEO_P_FRAME, payload(48));
        ByteBuf second = packet(0x81, SIM_20, Jt1078Constants.VIDEO_P_FRAME, payload(48));
        ByteBuf stream = Unpooled.buffer().writeBytes(first).writeBytes(second);

        assertEquals(SimWidth.EXTENDED,
                SimWidthDetector.detect(stream, stream.readerIndex()).width());
    }

    /** 透传包也要判对：它的头最短，越界风险最高。 */
    @Test
    void detectsTransparentPackets() {
        ByteBuf standard = packet(0x81, SIM_12, Jt1078Constants.TRANSPARENT_DATA, payload(16));
        ByteBuf extended = packet(0x81, SIM_20, Jt1078Constants.TRANSPARENT_DATA, payload(16));

        assertEquals(SimWidth.STANDARD,
                SimWidthDetector.detect(standard, standard.readerIndex()).width());
        assertEquals(SimWidth.EXTENDED,
                SimWidthDetector.detect(extended, extended.readerIndex()).width());
    }

    /** 畸形数据不能抛异常——解码器在它上面还要继续找下一个魔数。 */
    @Test
    void doesNotThrowOnGarbage() {
        ByteBuf garbage = Unpooled.buffer();
        garbage.writeBytes(Jt1078Constants.MAGIC);
        for (int i = 0; i < 40; i++) {
            garbage.writeByte(0xff);
        }

        SimWidthDetector.Outcome outcome =
                SimWidthDetector.detect(garbage, garbage.readerIndex());

        // 判成什么都可以，唯独不能抛。全 0xff 时两种解法都不成立，应当走兜底。
        assertEquals(SimWidth.STANDARD, outcome.width());
        assertTrue(outcome.tied(), "无依据时应标记为兜底，好让日志留下线索");
    }

    /** 缓冲区太短时不抛异常，由调用方决定等待。 */
    @Test
    void handlesTruncatedInput() {
        ByteBuf truncated = Unpooled.buffer();
        truncated.writeBytes(Jt1078Constants.MAGIC);
        truncated.writeByte(0x81);

        SimWidthDetector.Outcome outcome =
                SimWidthDetector.detect(truncated, truncated.readerIndex());

        assertEquals(SimWidth.STANDARD, outcome.width());
    }
}
