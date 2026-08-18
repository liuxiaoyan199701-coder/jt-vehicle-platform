package io.github.jtplatform.media.protocol;

import java.util.concurrent.atomic.LongAdder;

/**
 * 按 SIM 宽度统计已判定的流数。
 *
 * <p><b>为什么值得单独暴露</b>：对接新设备时，「画面是花的」几乎不给任何线索——连接正常、
 * 日志干净、其它指标正常。这两个计数是第一手诊断依据：如果新接的设备出现在
 * {@code bcd10} 这一边，说明它用的是 20 位 SIM，问题多半就在这条路上；
 * 如果 {@code tied} 不为零，说明有流是在没有依据的情况下按标准兜底跑的。
 *
 * <p>进程级累计而不是当前值：流会不断建立与断开，累计数才能回答「今天有没有过非标设备」。
 * 用 {@link LongAdder} 而不是 {@code AtomicLong}——判定发生在每条连接的第一个包上，
 * 高并发建流时写竞争明显，而读取只发生在拉取指标时。
 */
public final class SimWidthStats {

    private static final LongAdder STANDARD = new LongAdder();
    private static final LongAdder EXTENDED = new LongAdder();
    private static final LongAdder TIED = new LongAdder();

    private SimWidthStats() {
    }

    static void record(SimWidth width, boolean tied) {
        if (width == SimWidth.EXTENDED) {
            EXTENDED.increment();
        } else {
            STANDARD.increment();
        }
        if (tied) {
            TIED.increment();
        }
    }

    /** 判定为 1078-2016 标准 12 位 SIM 的流数。 */
    public static long standardStreams() {
        return STANDARD.sum();
    }

    /** 判定为 20 位 SIM（非标准）的流数。 */
    public static long extendedStreams() {
        return EXTENDED.sum();
    }

    /**
     * 两种解法打平、按标准兜底的流数。
     *
     * <p><b>这个数不为零就该看一眼</b>：它意味着判定其实没有依据，而猜错的表现是花屏。
     */
    public static long undecidedStreams() {
        return TIED.sum();
    }

    /** 仅供测试重置。 */
    static void reset() {
        STANDARD.reset();
        EXTENDED.reset();
        TIED.reset();
    }
}
