package io.github.jtplatform.common.service;

import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import io.github.jtplatform.common.port.StreamRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class MediaScheduler {
    private final MediaInstanceRegistry instances;
    private final StreamRegistry streams;
    private final Clock clock;
    private final Duration heartbeatTtl;
    private final double saturationThreshold;

    public MediaScheduler(
            MediaInstanceRegistry instances,
            StreamRegistry streams,
            Clock clock,
            Duration heartbeatTtl,
            double saturationThreshold) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatTtl = requirePositive(heartbeatTtl, "heartbeatTtl");
        if (saturationThreshold <= 0 || saturationThreshold > 1) {
            throw new IllegalArgumentException("saturationThreshold must be in range (0, 1]");
        }
        this.saturationThreshold = saturationThreshold;
    }

    public MediaInstance pick(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        Map<String, MediaInstance> active = instances.activeAfter(clock.instant().minus(heartbeatTtl)).stream()
                .collect(Collectors.toMap(MediaInstance::instanceId, Function.identity()));

        MediaInstance affinity = streams.entries().stream()
                .filter(entry -> entry.streamKey().deviceId().equals(streamKey.deviceId()))
                .filter(entry -> entry.state() != StreamState.DEAD)
                .map(StreamEntry::mediaInstanceId)
                .distinct()
                .map(active::get)
                .filter(Objects::nonNull)
                .filter(instance -> instance.hasCapacity(saturationThreshold))
                .findFirst()
                .orElse(null);
        if (affinity != null) {
            return affinity;
        }

        return active.values().stream()
                .filter(instance -> instance.hasCapacity(saturationThreshold))
                .min(Comparator.comparingDouble(MediaInstance::bandwidthUtilization)
                        .thenComparingDouble(MediaInstance::streamUtilization)
                        .thenComparing(MediaInstance::instanceId))
                .orElseThrow(NoMediaCapacityException::new);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
