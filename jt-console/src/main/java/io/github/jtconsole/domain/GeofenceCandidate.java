package io.github.jtconsole.domain;

import java.util.List;

/** 供位置判定使用的围栏快照。 */
public record GeofenceCandidate(
        long id,
        String name,
        double centerGcjLat,
        double centerGcjLng,
        double radiusMeters,
        GeofenceShape shape,
        List<double[]> points,
        boolean alertOnEnter,
        boolean alertOnExit,
        Double speedLimitKph) {

    public GeofenceCandidate {
        points = points == null ? List.of() : List.copyOf(points);
        shape = shape == null ? GeofenceShape.CIRCLE : shape;
    }
}
