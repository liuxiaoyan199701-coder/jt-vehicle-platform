package org.yzh.protocol.t1078.codec;

public final class Jt1078WireConstants {
    public static final int MAGIC = 0x30316364;
    /** 1078-2016 固定的第 4 字节：V=2, P=0, X=0, CC=1。SIM 字段为 BCD[6]（12 位）。 */
    public static final int FIXED_RTP_HEADER = 0x81;
    /**
     * 扩展形态：在 {@link #FIXED_RTP_HEADER} 的基础上把 RTP 的 X（扩展）位置 1，SIM 字段变为
     * BCD[10]（20 位）。
     *
     * <p><b>为什么需要它</b>：JT/T 808-2019 把终端手机号扩到了 20 位，而 JT/T 1078 至今仍是
     * 2016 版、SIM 字段仍是 12 位，标准从未补上这个缺口。于是同一台车在信令通道上是 20 位、
     * 在音视频通道上只能塞 12 位。
     *
     * <p><b>为什么选 X 位而不是换帧头魔数</b>：X 本来就是 RTP 用于标识「头部有扩展」的位，
     * 1078 从未使用它，语义正好。更要紧的是魔数不变——旧平台至少还能正确分帧（虽然字段解错），
     * 而换魔数会让旧平台连包边界都找不到，直接丢弃全部数据。
     *
     * <p>注意：**野生设备不会设这一位**。它们只是闷头写 10 字节，因此接收侧的宽度判定
     * 不能依赖此标志，见 {@code SimWidthDetector}。
     */
    public static final int EXTENDED_RTP_HEADER = 0x91;
    /** X 位掩码。第 4 字节与它相与非零即表示 SIM 为 BCD[10]。 */
    public static final int EXTENSION_MASK = 0x10;
    public static final int MARKER_MASK = 0x80;

    /** SIM 字段字节数：标准 6 字节（12 位 BCD），扩展 10 字节（20 位 BCD）。 */
    public static final int SIM_BYTES_STANDARD = 6;
    public static final int SIM_BYTES_EXTENDED = 10;
    /** 扩展形态比标准形态长出来的字节数。其后所有字段的偏移都要加上它。 */
    public static final int EXTENDED_SIM_EXTRA = SIM_BYTES_EXTENDED - SIM_BYTES_STANDARD;

    public static final int TRANSPARENT_HEADER_LENGTH = 18;
    public static final int AUDIO_HEADER_LENGTH = 26;
    public static final int VIDEO_HEADER_LENGTH = 30;
    public static final int MAX_PAYLOAD_LENGTH = 0xffff;

    public static final int VIDEO_I_FRAME = 0;
    public static final int VIDEO_P_FRAME = 1;
    public static final int VIDEO_B_FRAME = 2;
    public static final int AUDIO_FRAME = 3;
    public static final int TRANSPARENT_DATA = 4;

    public static final int PT_H264 = 98;
    public static final int PT_H265 = 99;
    public static final int PT_G711A = 6;
    public static final int PT_G726 = 8;
    public static final int PT_AAC = 19;
    public static final int PT_PCM = 22;

    private Jt1078WireConstants() {
    }
}
