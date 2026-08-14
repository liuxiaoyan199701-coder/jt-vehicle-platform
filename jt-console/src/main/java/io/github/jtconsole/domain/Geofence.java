package io.github.jtconsole.domain;

import java.util.List;

/** GCJ-02 圆形电子围栏。 */
public record Geofence(
        Long id,
        String name,
        double centerGcjLat,
        double centerGcjLng,
        double radiusMeters,
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
    }
}
