package io.github.jtconsole.domain;

public record GeofenceCandidate(
        long id,
        String name,
        double centerGcjLat,
        double centerGcjLng,
        double radiusMeters,
        boolean alertOnEnter,
        boolean alertOnExit,
        Double speedLimitKph) {
}
