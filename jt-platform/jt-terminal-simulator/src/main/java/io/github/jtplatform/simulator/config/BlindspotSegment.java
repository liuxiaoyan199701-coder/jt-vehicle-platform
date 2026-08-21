package io.github.jtplatform.simulator.config;

/** 行程路线上的盲区区间，按路线进度百分比表达，闭区间起点、开区间终点。 */
public record BlindspotSegment(double startPercent, double endPercent) {
    public BlindspotSegment {
        if (!Double.isFinite(startPercent) || !Double.isFinite(endPercent)
                || startPercent < 0 || endPercent > 100 || startPercent >= endPercent) {
            throw new IllegalArgumentException("blindspot segment must satisfy 0 <= start < end <= 100");
        }
    }
}
