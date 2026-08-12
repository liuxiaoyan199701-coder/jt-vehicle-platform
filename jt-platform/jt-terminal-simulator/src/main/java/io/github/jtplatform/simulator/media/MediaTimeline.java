package io.github.jtplatform.simulator.media;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class MediaTimeline {
    private final long anchorEpochMillis;
    private final long anchorNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong lastTimestamp;

    public MediaTimeline() {
        this(Clock.systemUTC(), System::nanoTime);
    }

    MediaTimeline(Clock wallClock, LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.anchorEpochMillis = Objects.requireNonNull(wallClock, "wallClock").millis();
        if (anchorEpochMillis < 0) {
            throw new IllegalArgumentException("wall clock must not be before the epoch");
        }
        this.anchorNanos = nanoTime.getAsLong();
        this.lastTimestamp = new AtomicLong(anchorEpochMillis);
    }

    public long anchorEpochMillis() {
        return anchorEpochMillis;
    }

    public long nowMillis() {
        long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - anchorNanos);
        long candidate = saturatedAdd(anchorEpochMillis, elapsedNanos / 1_000_000L);
        return lastTimestamp.accumulateAndGet(candidate, Math::max);
    }

    public long timestampAfterSamples(long streamAnchorMillis, long samples, int sampleRate) {
        if (streamAnchorMillis < anchorEpochMillis) {
            throw new IllegalArgumentException("streamAnchorMillis must not precede the media timeline");
        }
        if (samples < 0 || sampleRate < 1) {
            throw new IllegalArgumentException("samples must be non-negative and sampleRate must be positive");
        }
        long elapsedMillis = samples > Long.MAX_VALUE / 1_000L
                ? Long.MAX_VALUE : samples * 1_000L / sampleRate;
        return saturatedAdd(streamAnchorMillis, elapsedMillis);
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
