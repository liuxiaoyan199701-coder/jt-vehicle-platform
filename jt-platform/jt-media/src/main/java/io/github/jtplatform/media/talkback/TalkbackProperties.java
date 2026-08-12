package io.github.jtplatform.media.talkback;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.media.talkback")
public class TalkbackProperties {
    private TalkbackMode mode = TalkbackMode.EXCLUSIVE;
    private Duration mixInterval = Duration.ofMillis(20);
    private int maxFrameBytes = 4096;
    private int maxQueuedFramesPerParticipant = 10;
    private int maxPendingDeviceWrites = 10;

    public TalkbackMode getMode() {
        return mode;
    }

    public void setMode(TalkbackMode mode) {
        this.mode = mode;
    }

    public Duration getMixInterval() {
        return mixInterval;
    }

    public void setMixInterval(Duration mixInterval) {
        this.mixInterval = mixInterval;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public void setMaxFrameBytes(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public int getMaxQueuedFramesPerParticipant() {
        return maxQueuedFramesPerParticipant;
    }

    public void setMaxQueuedFramesPerParticipant(int maxQueuedFramesPerParticipant) {
        this.maxQueuedFramesPerParticipant = maxQueuedFramesPerParticipant;
    }

    public int getMaxPendingDeviceWrites() {
        return maxPendingDeviceWrites;
    }

    public void setMaxPendingDeviceWrites(int maxPendingDeviceWrites) {
        this.maxPendingDeviceWrites = maxPendingDeviceWrites;
    }

    public void validate() {
        if (mode == null) {
            throw new IllegalStateException("jt.media.talkback.mode is required");
        }
        if (mixInterval == null || mixInterval.isZero() || mixInterval.isNegative()) {
            throw new IllegalStateException("jt.media.talkback.mix-interval must be positive");
        }
        long intervalMillis = mixInterval.toMillis();
        if (intervalMillis < 1 || !mixInterval.equals(Duration.ofMillis(intervalMillis))) {
            throw new IllegalStateException(
                    "jt.media.talkback.mix-interval must use whole milliseconds");
        }
        if (intervalMillis > 0xffffL / 8L) {
            throw new IllegalStateException(
                    "jt.media.talkback.mix-interval produces a frame larger than 65535 bytes");
        }
        if (maxFrameBytes < 1 || maxFrameBytes > 0xffff) {
            throw new IllegalStateException("jt.media.talkback.max-frame-bytes must be in range 1..65535");
        }
        if (maxQueuedFramesPerParticipant < 1) {
            throw new IllegalStateException(
                    "jt.media.talkback.max-queued-frames-per-participant must be positive");
        }
        if (maxPendingDeviceWrites < 1) {
            throw new IllegalStateException(
                    "jt.media.talkback.max-pending-device-writes must be positive");
        }
        try {
            long capacity = Math.multiplyExact(
                    (long) mixFrameBytes(), maxQueuedFramesPerParticipant);
            if (capacity > Integer.MAX_VALUE) {
                throw new ArithmeticException("capacity exceeds integer range");
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(
                    "jt.media.talkback queued audio capacity is too large", overflow);
        }
    }

    int mixFrameBytes() {
        return Math.toIntExact(mixInterval.toMillis() * 8L);
    }
}
