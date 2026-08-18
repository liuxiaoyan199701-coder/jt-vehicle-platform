package io.github.jtplatform.media.protocol;

public final class Jt1078Constants {
    public static final byte[] MAGIC = {0x30, 0x31, 0x63, 0x64};
    /** 同一个魔数的 int 形态，用于一次比较四个字节（判定包边界是否对齐）。 */
    public static final int MAGIC_INT = 0x30316364;
    /**
     * RTP 头第 4 字节的 X（扩展）位。
     *
     * <p>1078-2016 把该字节固定为 {@code 0x81}（X=0），从未使用这一位。本平台约定：
     * 置 1 表示 SIM 字段为 BCD[10]（20 位终端手机号）。**野生设备不会设它**，
     * 它们只是闷头写 10 字节，所以判定不能只依赖这个标志。
     */
    public static final int EXTENSION_MASK = 0x10;
    /** 1078-2016 固定的第 4 字节：V=2, P=0, X=0, CC=1。 */
    public static final int FIXED_RTP_HEADER = 0x81;
    /**
     * 扩展形态的第 4 字节：在固定值基础上置 X 位。
     *
     * <p>判定时**比对整个字节**而不是只看 X 位：只判 {@code & 0x10} 的话，任何恰好带这一位的
     * 噪声都会被当成扩展标志（全 {@code 0xff} 的垃圾数据就是如此），而那时其余字段根本无从解析。
     */
    public static final int EXTENDED_RTP_HEADER = 0x91;
    public static final int COMMON_HEADER_LENGTH = 16;
    public static final int TRANSPARENT_HEADER_LENGTH = 18;
    public static final int AUDIO_HEADER_LENGTH = 26;
    public static final int VIDEO_HEADER_LENGTH = 30;

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

    private Jt1078Constants() {}
}
