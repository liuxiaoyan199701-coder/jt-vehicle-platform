package io.github.jtconsole.domain;

import java.util.List;

/**
 * 电子围栏。{@code shape} 决定几何语义：circle 用圆心+半径，rectangle 用两个对角顶点，
 * polygon 用至少 3 个顶点，route 用至少 2 个途经点 + 走廊半宽（复用 radiusMeters）。
 * 顶点均为 GCJ-02 坐标，{@code points} 里每个元素是 {@code [lat, lng]}。
 */
public record Geofence(
        Long id,
        String name,
        double centerGcjLat,
        double centerGcjLng,
        double radiusMeters,
        GeofenceShape shape,
        List<double[]> points,
        String color,
        boolean enabled,
        boolean alertOnEnter,
        boolean alertOnExit,
        Double speedLimitKph,
        List<String> vehicleIds,
        int assignedVehicleCount,
        Long tenantId,
        String createdAt,
        String updatedAt) {

    public Geofence {
        vehicleIds = vehicleIds == null ? List.of() : List.copyOf(vehicleIds);
        points = points == null ? List.of() : List.copyOf(points);
        shape = shape == null ? GeofenceShape.CIRCLE : shape;
    }
}
