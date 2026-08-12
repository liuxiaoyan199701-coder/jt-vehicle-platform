package io.github.jtplatform.simulator.stream;

import io.github.jtplatform.simulator.media.MediaStats;
import java.util.Objects;
import java.util.Optional;

public record MediaSnapshot(
        MediaState state,
        Optional<MediaTarget> target,
        int mediaType,
        int streamType,
        boolean audioEnabled,
        boolean videoEnabled,
        boolean previewEnabled,
        MediaStats.Snapshot stats,
        String detail) {
    public MediaSnapshot {
        Objects.requireNonNull(state, "state");
        target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(stats, "stats");
        detail = detail == null ? "" : detail;
    }
}
