package io.github.jtplatform.media.protocol;

/**
 * RTP 头里 SIM 卡号字段的宽度。
 *
 * <p>JT/T 1078-2016 规定为 BCD[6]（12 位）。但 JT/T 808-2019 把终端手机号扩到了 20 位，
 * 而 1078 至今未跟进——标准留着这个缺口没补。于是厂商各自解释，出现了两类设备：
 * 守标准的把号码截断塞进 12 位，不守标准的直接写 10 字节。
 *
 * <p>后者会让**整个头部长出 4 字节**，其后每一个字段——通道号、帧类型、时间戳、数据体长度
 * ——全部偏移。按标准解析的结果是通道号和帧类型都是垃圾，表现为「流连上了但画面是花的」。
 */
public enum SimWidth {

    /** 1078-2016 标准：6 字节 BCD，12 位数字。 */
    STANDARD(6),

    /** 20 位终端手机号：10 字节 BCD。非 1078 标准，但现实中存在。 */
    EXTENDED(10);

    /** 扩展形态相对标准形态多出来的字节数。 */
    public static final int EXTRA_BYTES = EXTENDED.bytes - STANDARD.bytes;

    private final int bytes;

    SimWidth(int bytes) {
        this.bytes = bytes;
    }

    public int bytes() {
        return bytes;
    }

    /** 该宽度下，SIM 字段之后的字段需要额外偏移多少字节。 */
    public int offsetShift() {
        return bytes - STANDARD.bytes;
    }

    public boolean nonStandard() {
        return this == EXTENDED;
    }
}
