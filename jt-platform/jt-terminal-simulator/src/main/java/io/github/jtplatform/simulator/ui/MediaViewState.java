package io.github.jtplatform.simulator.ui;

import java.util.Objects;

public record MediaViewState(
        String state,
        String target,
        String stream,
        boolean audioEnabled,
        boolean videoEnabled,
        double framesPerSecond,
        double bitsPerSecond,
        int queueDepth,
        long droppedFrames,
        String detail) {

    public MediaViewState {
        state = normalize(state, "IDLE");
        target = normalize(target, "-");
        stream = normalize(stream, "-");
        if (!Double.isFinite(framesPerSecond) || framesPerSecond < 0) {
            throw new IllegalArgumentException("framesPerSecond must be finite and non-negative");
        }
        if (!Double.isFinite(bitsPerSecond) || bitsPerSecond < 0) {
            throw new IllegalArgumentException("bitsPerSecond must be finite and non-negative");
        }
        if (queueDepth < 0 || droppedFrames < 0) {
            throw new IllegalArgumentException("queue and drop counters must be non-negative");
        }
        detail = detail == null ? "" : detail.trim();
    }

    public static MediaViewState idle() {
        return new MediaViewState(
                "IDLE", "-", "-", false, false, 0, 0, 0, 0, "");
    }

    private static String normalize(String value, String fallback) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
