package io.github.jtplatform.media.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.jtplatform.common.model.StreamKind;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.yzh.protocol.t1078.codec.Jt1078FragmentFlag;
import org.yzh.protocol.t1078.codec.Jt1078PacketEncoder;
import org.yzh.protocol.t1078.codec.Jt1078PacketHeader;
import org.yzh.protocol.t1078.codec.Jt1078SimFormat;
import org.yzh.protocol.t1078.codec.Jt1078WireConstants;

/**
 * 三种 SIM 形态的编解码回环。
 *
 * <p><b>这是本次改动真正的验收点</b>：编码侧按某种形态发出去，解码侧在**不被告知形态**的前提下
 * 必须解出同样的设备号、通道号、帧类型与负载。其中 {@code NON_STANDARD_20} 复现的是野生设备的
 * 行为——写 10 字节 SIM 却不置 X 位，接收方只能靠投票判定。
 *
 * <p>判错的表现不是报错而是花屏：通道号和帧类型读的是相邻字段拼出来的随机数，
 * 解码器一路不抛异常。所以必须逐字段断言，不能只看「解出来了」。
 */
class SimWidthRoundTripTest {

    /** 20 位形态下能完整承载的设备号：11 位真实号码，左侧补零。 */
    private static final String DEVICE_ID = "13800138000";

    private static byte[] encode(Jt1078SimFormat format, int dataType, byte[] payload) {
        Jt1078PacketHeader header = new Jt1078PacketHeader(
                Jt1078WireConstants.PT_H264,
                true,
                7,
                DEVICE_ID,
                3,
                dataType,
                Jt1078FragmentFlag.ATOMIC,
                123_456_789L,
                40,
                20);
        return new Jt1078PacketEncoder(format).encode(header, payload);
    }

    @ParameterizedTest
    @EnumSource(Jt1078SimFormat.class)
    void videoPacketSurvivesRoundTrip(Jt1078SimFormat format) {
        byte[] payload = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] wire = encode(format, Jt1078Constants.VIDEO_I_FRAME, payload);

        EmbeddedChannel channel =
                new EmbeddedChannel(new Jt1078RtpDecoder(StreamKind.MAIN, 65_535));
        channel.writeInbound(Unpooled.wrappedBuffer(wire));
        RtpPacket decoded = channel.readInbound();

        assertNotNull(decoded, format + " 的包必须能被解出来");
        assertEquals(DEVICE_ID, decoded.header().deviceId(), format + " 的设备号解错了");
        assertEquals(3, decoded.header().channel(), format + " 的通道号解错了");
        assertEquals(Jt1078Constants.VIDEO_I_FRAME, decoded.header().dataType());
        assertEquals(123_456_789L, decoded.header().timestamp());
        assertArrayEquals(payload, decoded.payload());
        channel.finishAndReleaseAll();
    }

    @ParameterizedTest
    @EnumSource(Jt1078SimFormat.class)
    void audioPacketSurvivesRoundTrip(Jt1078SimFormat format) {
        byte[] payload = {9, 8, 7};
        byte[] wire = encode(format, Jt1078Constants.AUDIO_FRAME, payload);

        EmbeddedChannel channel =
                new EmbeddedChannel(new Jt1078RtpDecoder(StreamKind.MAIN, 65_535));
        channel.writeInbound(Unpooled.wrappedBuffer(wire));
        RtpPacket decoded = channel.readInbound();

        assertNotNull(decoded, format + " 的音频包必须能被解出来");
        assertEquals(DEVICE_ID, decoded.header().deviceId());
        assertEquals(3, decoded.header().channel());
        assertEquals(Jt1078Constants.AUDIO_FRAME, decoded.header().dataType());
        assertArrayEquals(payload, decoded.payload());
        channel.finishAndReleaseAll();
    }

    /**
     * 连续多包走同一条连接：判定应当只发生一次，后续包沿用缓存。
     *
     * <p>这正是把结果缓存在连接上的理由——逐包判定时，只要有一个包让投票倒向另一边，
     * 同一条流的前后帧就会按不同偏移解析，画面时好时坏且日志看不出规律。
     */
    @Test
    void stickyAcrossManyPacketsOnTheSameConnection() {
        EmbeddedChannel channel =
                new EmbeddedChannel(new Jt1078RtpDecoder(StreamKind.MAIN, 65_535));

        for (int index = 0; index < 5; index++) {
            byte[] payload = {(byte) index, (byte) (index + 1)};
            channel.writeInbound(Unpooled.wrappedBuffer(
                    encode(Jt1078SimFormat.NON_STANDARD_20, Jt1078Constants.VIDEO_P_FRAME, payload)));
            RtpPacket decoded = channel.readInbound();
            assertNotNull(decoded, "第 " + (index + 1) + " 个包应当解出来");
            assertEquals(DEVICE_ID, decoded.header().deviceId());
            assertArrayEquals(payload, decoded.payload());
        }
        channel.finishAndReleaseAll();
    }

    /**
     * 标准形态放不下 20 位，取末 12 位。
     *
     * <p>对大陆 11 位手机号无损（左侧本就是补的零），这里明确断言这一行为，
     * 免得日后有人以为标准形态也能完整承载 20 位。
     */
    @Test
    void standardFormatTruncatesToTheLastTwelveDigits() {
        String twentyDigits = "00000000001380013800";
        Jt1078PacketHeader header = new Jt1078PacketHeader(
                Jt1078WireConstants.PT_H264, true, 1, twentyDigits, 1,
                Jt1078Constants.VIDEO_I_FRAME, Jt1078FragmentFlag.ATOMIC, 1L, 0, 0);
        byte[] wire = new Jt1078PacketEncoder(Jt1078SimFormat.STANDARD).encode(header, new byte[] {1});

        EmbeddedChannel channel =
                new EmbeddedChannel(new Jt1078RtpDecoder(StreamKind.MAIN, 65_535));
        channel.writeInbound(Unpooled.wrappedBuffer(wire));
        RtpPacket decoded = channel.readInbound();

        assertNotNull(decoded);
        assertEquals("1380013800", decoded.header().deviceId(), "应保留末 12 位并去掉前导零");
        channel.finishAndReleaseAll();
    }

    /** 扩展形态能完整承载 20 位，不丢信息。 */
    @Test
    void extendedFormatCarriesAllTwentyDigits() {
        String twentyDigits = "12345678901234567890";
        Jt1078PacketHeader header = new Jt1078PacketHeader(
                Jt1078WireConstants.PT_H264, true, 1, twentyDigits, 1,
                Jt1078Constants.VIDEO_I_FRAME, Jt1078FragmentFlag.ATOMIC, 1L, 0, 0);
        byte[] wire = new Jt1078PacketEncoder(Jt1078SimFormat.EXTENDED).encode(header, new byte[] {1});

        EmbeddedChannel channel =
                new EmbeddedChannel(new Jt1078RtpDecoder(StreamKind.MAIN, 65_535));
        channel.writeInbound(Unpooled.wrappedBuffer(wire));
        RtpPacket decoded = channel.readInbound();

        assertNotNull(decoded);
        assertEquals(twentyDigits, decoded.header().deviceId());
        channel.finishAndReleaseAll();
    }
}
