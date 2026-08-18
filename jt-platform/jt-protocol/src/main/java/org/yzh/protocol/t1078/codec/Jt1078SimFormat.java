package org.yzh.protocol.t1078.codec;

/**
 * RTP 头里 SIM 卡号字段的编码形态。
 *
 * <p>JT/T 808-2019 把终端手机号扩到了 20 位，而 JT/T 1078 至今仍是 2016 版、SIM 字段仍是
 * 12 位——标准从未补上这个缺口。现实中因此出现三种设备行为，这个枚举把它们显式列出来，
 * 便于对接调试与自动化验证。
 */
public enum Jt1078SimFormat {

    /**
     * 1078-2016 标准：{@code BCD[6]}，第 4 字节 {@code 0x81}。
     *
     * <p>20 位号码在这里放不下，取末 12 位——**这会丢掉前 8 位**。对大陆 11 位手机号无损
     * （前面本来就是补的零），但若厂商把设备序列号之类塞进 20 位字段，截断就是有损的。
     */
    STANDARD(Jt1078WireConstants.SIM_BYTES_STANDARD, Jt1078WireConstants.FIXED_RTP_HEADER),

    /**
     * 平台扩展：{@code BCD[10]}，第 4 字节 {@code 0x91}（置 RTP 的 X 位）。
     *
     * <p>20 位原样编码，不丢信息，且接收侧能通过 X 位一眼认出，无需猜测。
     * 这是我们向厂商建议的形态。
     */
    EXTENDED(Jt1078WireConstants.SIM_BYTES_EXTENDED, Jt1078WireConstants.EXTENDED_RTP_HEADER),

    /**
     * 野生形态：{@code BCD[10]}，但第 4 字节仍是 {@code 0x81}（不置 X 位）。
     *
     * <p><b>这不是给生产用的，是给测试用的。</b>现实中确实有设备这么干——直接写 10 字节却不做
     * 任何标记，于是整个头部长出 4 字节而接收方毫不知情。模拟器需要能复现这种行为，
     * 否则接收侧的自动识别就没有办法端到端验证。
     */
    NON_STANDARD_20(Jt1078WireConstants.SIM_BYTES_EXTENDED, Jt1078WireConstants.FIXED_RTP_HEADER);

    private final int simBytes;
    private final int rtpHeaderByte;

    Jt1078SimFormat(int simBytes, int rtpHeaderByte) {
        this.simBytes = simBytes;
        this.rtpHeaderByte = rtpHeaderByte;
    }

    public int simBytes() {
        return simBytes;
    }

    public int rtpHeaderByte() {
        return rtpHeaderByte;
    }

    /** SIM 字段之后的字段相对标准形态要额外偏移多少字节。 */
    public int offsetShift() {
        return simBytes - Jt1078WireConstants.SIM_BYTES_STANDARD;
    }

    public int simDigits() {
        return simBytes * 2;
    }
}
