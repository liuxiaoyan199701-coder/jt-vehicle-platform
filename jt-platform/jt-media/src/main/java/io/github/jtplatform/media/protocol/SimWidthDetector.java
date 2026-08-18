package io.github.jtplatform.media.protocol;

import io.netty.buffer.ByteBuf;

/**
 * 判定一条 1078 流的 SIM 字段是 12 位还是 20 位。
 *
 * <p><b>只在连接的第一个可解包上跑一次</b>，结果缓存在连接上（见 {@code SimWidthResolver}）。
 * 设备不会中途换格式；每包都探测既浪费，又会在遇到一个畸形包时来回抖动，
 * 那会让同一条流的前后帧按不同偏移解析——比一直解错更难查。
 *
 * <p>X 位已置 1 时直接采信，跳过投票。但**野生设备不会设这一位**，它们只是闷头写 10 字节，
 * 所以投票路径才是主路径。
 *
 * <h2>为什么需要三个信号</h2>
 *
 * 存在一个无法用包边界区分的真实碰撞：{@code 视频帧+BCD[6]} 的头长是 30 字节，
 * {@code 音频帧+BCD[10]} 的头长也是 30 字节，而且数据体长度字段落在**同一个偏移**
 * （headerLength-2）。两种解法会读出同一个长度、算出同一个包边界。
 * 只有前导零信号能把这两种分开。
 */
public final class SimWidthDetector {

    /** 单看边界对齐的权重。它最可靠，但有上述盲区。 */
    private static final int SCORE_BOUNDARY = 4;
    /** 字段取值合法的权重。 */
    private static final int SCORE_FIELDS = 2;
    /** 前导零模式的权重。专治边界盲区，所以必须能在其它两项打平时决出胜负。 */
    private static final int SCORE_LEADING_ZEROS = 3;

    private SimWidthDetector() {
    }

    /**
     * 判定宽度。
     *
     * @param input 至少已定位到魔数的缓冲区，方法不消费任何字节
     * @param start 魔数所在位置
     * @return 判定结果，附带是否为「两种解法打平后按标准兜底」
     */
    public static Outcome detect(ByteBuf input, int start) {
        // X 位是我们自己定义的扩展标志，设了就直接信，不必投票。
        //
        // 但必须比对**整个字节**而不是单看那一位：1078 把第 4 字节固定为 0x81，扩展形态就是
        // 精确的 0x91。只判 `& 0x10` 的话，任何恰好带这一位的噪声都会被当成扩展标志——
        // 全 0xff 的垃圾数据就是如此，而那时其余字段根本无从解析。
        if (readable(input, start, start + 5)
                && input.getUnsignedByte(start + 4) == Jt1078Constants.EXTENDED_RTP_HEADER) {
            return new Outcome(SimWidth.EXTENDED, false, "X 位已置位");
        }

        int standard = score(input, start, SimWidth.STANDARD);
        int extended = score(input, start, SimWidth.EXTENDED);

        if (standard > extended) {
            return new Outcome(SimWidth.STANDARD, false, describe(standard, extended));
        }
        if (extended > standard) {
            return new Outcome(SimWidth.EXTENDED, false, describe(standard, extended));
        }
        // 打平：按标准继续，但标记出来。这一分支应当极罕见，日志是它出现时唯一的线索。
        return new Outcome(SimWidth.STANDARD, true, describe(standard, extended));
    }

    /** 按某种解法给三个信号打分。任何一步越界都当作该信号不成立，不抛异常。 */
    private static int score(ByteBuf input, int start, SimWidth width) {
        int shift = width.offsetShift();
        int typeOffset = start + 15 + shift;
        if (!readable(input, start, typeOffset + 1)) {
            return 0;
        }
        int typeAndFragment = input.getUnsignedByte(typeOffset);
        int dataType = (typeAndFragment >>> 4) & 0x0f;
        int fragment = typeAndFragment & 0x0f;

        int headerLength = headerLength(dataType, shift);
        if (headerLength < 0) {
            // 帧类型都不合法，这条解法直接出局——继续算下去只会拿到随机数。
            return 0;
        }

        int total = 0;
        int channel = readable(input, start, start + 15 + shift)
                ? input.getUnsignedByte(start + 14 + shift) : -1;
        if (channel >= 1 && channel <= 0xff && fragment <= 3) {
            total += SCORE_FIELDS;
        }
        if (boundaryAligned(input, start, headerLength)) {
            total += SCORE_BOUNDARY;
        }
        if (leadingZeroPatternMatches(input, start, width)) {
            total += SCORE_LEADING_ZEROS;
        }
        return total;
    }

    /**
     * 按该解法算出的包尾是否正好落在下一个包的魔数上，或正好是缓冲区末尾。
     *
     * <p>这是最强的单一信号：连续两个 4 字节魔数对齐，随机命中的概率可以忽略。
     */
    private static boolean boundaryAligned(ByteBuf input, int start, int headerLength) {
        int lengthOffset = start + headerLength - Short.BYTES;
        if (!readable(input, start, lengthOffset + Short.BYTES)) {
            return false;
        }
        int bodyLength = input.getUnsignedShort(lengthOffset);
        int next = start + headerLength + bodyLength;
        int end = input.readerIndex() + input.readableBytes();
        if (next == end) {
            return true;
        }
        if (next + Integer.BYTES > end) {
            return false;
        }
        return input.getInt(next) == Jt1078Constants.MAGIC_INT;
    }

    /**
     * 前导零模式。
     *
     * <p>真实号码 11–13 位。填进 BCD[10] 的 20 位里，前 3–4 个字节必然全是 {@code 0x00}；
     * 而填进 BCD[6] 的 12 位里，最多只有半个字节是零（11 位号码补 1 个前导零）。
     *
     * <p>所以偏移 8 起连续 4 字节全零，强烈指向 BCD[10]；反之若第 1 字节就有非零高位，
     * 强烈指向 BCD[6]。**这是唯一能解开 30 字节碰撞的信号。**
     */
    private static boolean leadingZeroPatternMatches(ByteBuf input, int start, SimWidth width) {
        if (!readable(input, start, start + 8 + 4)) {
            return false;
        }
        boolean fourZeroBytes = input.getUnsignedByte(start + 8) == 0
                && input.getUnsignedByte(start + 9) == 0
                && input.getUnsignedByte(start + 10) == 0
                && input.getUnsignedByte(start + 11) == 0;
        return width == SimWidth.EXTENDED ? fourZeroBytes : !fourZeroBytes;
    }

    /** 帧类型对应的头长，加上 SIM 扩展带来的偏移。类型非法返回 -1。 */
    private static int headerLength(int dataType, int shift) {
        return switch (dataType) {
            case Jt1078Constants.VIDEO_I_FRAME,
                 Jt1078Constants.VIDEO_P_FRAME,
                 Jt1078Constants.VIDEO_B_FRAME -> Jt1078Constants.VIDEO_HEADER_LENGTH + shift;
            case Jt1078Constants.AUDIO_FRAME -> Jt1078Constants.AUDIO_HEADER_LENGTH + shift;
            case Jt1078Constants.TRANSPARENT_DATA ->
                    Jt1078Constants.TRANSPARENT_HEADER_LENGTH + shift;
            default -> -1;
        };
    }

    private static boolean readable(ByteBuf input, int start, int exclusiveEnd) {
        return exclusiveEnd <= input.readerIndex() + input.readableBytes();
    }

    private static String describe(int standard, int extended) {
        return "标准 " + standard + " 分 / 扩展 " + extended + " 分";
    }

    /**
     * @param tied   两种解法打平、按标准兜底时为 true。**这一分支值得记 WARN**：
     *               它意味着判定其实没有依据，而猜错的表现是花屏
     * @param reason 判定依据，只用于日志
     */
    public record Outcome(SimWidth width, boolean tied, String reason) {}
}
