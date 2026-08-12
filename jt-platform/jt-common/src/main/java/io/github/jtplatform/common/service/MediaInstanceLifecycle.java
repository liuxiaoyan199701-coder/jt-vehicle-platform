package io.github.jtplatform.common.service;

import io.github.jtplatform.common.port.MediaInstanceRegistry;
import io.github.jtplatform.common.port.StreamRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class MediaInstanceLifecycle {
    private final MediaInstanceRegistry instances;
    private final StreamRegistry streams;
    private final Clock clock;
    private final Duration heartbeatTtl;

    public MediaInstanceLifecycle(
            MediaInstanceRegistry instances,
            StreamRegistry streams,
            Clock clock,
            Duration heartbeatTtl) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatTtl = Objects.requireNonNull(heartbeatTtl, "heartbeatTtl");
        if (heartbeatTtl.isZero() || heartbeatTtl.isNegative()) {
            throw new IllegalArgumentException("heartbeatTtl must be positive");
        }
    }

    public List<String> expireStaleInstances() {
        List<String> expired = instances.removeExpiredBefore(clock.instant().minus(heartbeatTtl));
        expired.forEach(instanceId -> streams.invalidateMediaInstance(instanceId, "media instance heartbeat expired"));
        return expired;
    }
}
