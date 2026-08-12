package io.github.jtplatform.media.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Samples distinct active ingress streams and successfully written raw WebSocket bytes.
 * The byte rate includes the JT78 application frame header and excludes transport framing.
 */
public final class MediaNodeLoadMonitor {
    private final IntSupplier currentStreams;
    private final LongSupplier outboundBytes;
    private final Clock clock;

    private Instant previousSampleAt;
    private long previousOutboundBytes;
    private volatile long outboundBitsPerSecond;

    public MediaNodeLoadMonitor(IntSupplier currentStreams, LongSupplier outboundBytes, Clock clock) {
        this.currentStreams = Objects.requireNonNull(currentStreams, "currentStreams");
        this.outboundBytes = Objects.requireNonNull(outboundBytes, "outboundBytes");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.previousSampleAt = clock.instant();
        this.previousOutboundBytes = nonNegative(outboundBytes.getAsLong());
    }

    public synchronized LoadSnapshot sample() {
        Instant now = clock.instant();
        long totalBytes = nonNegative(outboundBytes.getAsLong());
        Duration elapsed = Duration.between(previousSampleAt, now);
        if (elapsed.isNegative() || elapsed.isZero() || totalBytes < previousOutboundBytes) {
            outboundBitsPerSecond = 0;
        } else {
            long byteDelta = totalBytes - previousOutboundBytes;
            double rate = byteDelta * 8.0d / (elapsed.toNanos() / 1_000_000_000.0d);
            outboundBitsPerSecond = rate >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(rate);
        }
        previousSampleAt = now;
        previousOutboundBytes = totalBytes;
        return snapshot();
    }

    public LoadSnapshot snapshot() {
        return new LoadSnapshot(Math.max(0, currentStreams.getAsInt()), outboundBitsPerSecond);
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    public record LoadSnapshot(int currentStreams, long outboundBitsPerSecond) {}
}
