package io.github.jtplatform.simulator.media;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class MediaStats {
    private final Clock clock;
    private final long startedAtMillis;
    private final LongAdder producedVideoFrames = new LongAdder();
    private final LongAdder producedAudioFrames = new LongAdder();
    private final LongAdder sentVideoFrames = new LongAdder();
    private final LongAdder sentAudioFrames = new LongAdder();
    private final LongAdder sentBytes = new LongAdder();
    private final LongAdder droppedPFrames = new LongAdder();
    private final LongAdder droppedOtherFrames = new LongAdder();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicInteger maximumQueueDepth = new AtomicInteger();
    private final AtomicLong lastSentTimestampMillis = new AtomicLong(-1L);

    public MediaStats() {
        this(Clock.systemUTC());
    }

    MediaStats(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAtMillis = clock.millis();
    }

    public void recordProduced(MediaFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.type().video()) {
            producedVideoFrames.increment();
        } else {
            producedAudioFrames.increment();
        }
    }

    public void recordDropped(MediaFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.type() == MediaFrameType.VIDEO_P) {
            droppedPFrames.increment();
        } else {
            droppedOtherFrames.increment();
        }
    }

    public void recordSent(MediaFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.type().video()) {
            sentVideoFrames.increment();
        } else {
            sentAudioFrames.increment();
        }
        sentBytes.add(frame.payloadLength());
        lastSentTimestampMillis.accumulateAndGet(frame.timestampMillis(), Math::max);
    }

    public void recordQueueDepth(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative");
        }
        queueDepth.set(depth);
        maximumQueueDepth.accumulateAndGet(depth, Math::max);
    }

    public Snapshot snapshot() {
        long elapsedMillis = Math.max(1L, clock.millis() - startedAtMillis);
        long sentVideo = sentVideoFrames.sum();
        long bytes = sentBytes.sum();
        return new Snapshot(
                producedVideoFrames.sum(),
                producedAudioFrames.sum(),
                sentVideo,
                sentAudioFrames.sum(),
                bytes,
                droppedPFrames.sum(),
                droppedOtherFrames.sum(),
                queueDepth.get(),
                maximumQueueDepth.get(),
                lastSentTimestampMillis.get(),
                sentVideo * 1_000.0d / elapsedMillis,
                bytes * 8_000.0d / elapsedMillis);
    }

    public record Snapshot(
            long producedVideoFrames,
            long producedAudioFrames,
            long sentVideoFrames,
            long sentAudioFrames,
            long sentBytes,
            long droppedPFrames,
            long droppedOtherFrames,
            int queueDepth,
            int maximumQueueDepth,
            long lastSentTimestampMillis,
            double sentVideoFramesPerSecond,
            double sentBitsPerSecond) {
    }
}
