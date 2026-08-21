package io.github.jtplatform.simulator.signal;

import java.time.Duration;
import java.util.Objects;

/** 车队同路线错峰策略：索引决定出发延迟与路线起点偏移。 */
public record FleetSchedule(Duration departureDelay, double routeStartOffsetMeters) {
    public FleetSchedule {
        Objects.requireNonNull(departureDelay, "departureDelay");
        if (departureDelay.isNegative()) {
            throw new IllegalArgumentException("departureDelay must not be negative");
        }
        if (!Double.isFinite(routeStartOffsetMeters) || routeStartOffsetMeters < 0) {
            throw new IllegalArgumentException("routeStartOffsetMeters must be finite and non-negative");
        }
    }

    public static FleetSchedule forIndex(int index, int departureIntervalSeconds) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        if (departureIntervalSeconds < 0) {
            throw new IllegalArgumentException("departureIntervalSeconds must not be negative");
        }
        return new FleetSchedule(Duration.ofSeconds((long) index * departureIntervalSeconds), index * 100.0D);
    }
}
