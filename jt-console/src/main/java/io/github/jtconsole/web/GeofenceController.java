package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.Geofence;
import io.github.jtconsole.operations.GeofenceService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geofences")
public class GeofenceController {

    private final GeofenceService geofences;

    public GeofenceController(GeofenceService geofences) {
        this.geofences = geofences;
    }

    @GetMapping
    public ApiResponse<List<Geofence>> list() {
        return ApiResponse.ok(geofences.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<Geofence> get(@PathVariable long id) {
        return geofences.findById(id).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "围栏不存在"));
    }

    @PostMapping
    public ApiResponse<Geofence> create(@RequestBody GeofenceRequest body) {
        return ApiResponse.ok(geofences.create(body.toDomain()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Geofence> update(@PathVariable long id, @RequestBody GeofenceRequest body) {
        return geofences.update(id, body.toDomain()).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "围栏不存在"));
    }

    @PutMapping("/{id}/vehicles")
    public ApiResponse<Geofence> replaceVehicles(
            @PathVariable long id, @RequestBody VehicleAssignments body) {
        List<String> ids = body == null ? null : body.deviceIds();
        return geofences.replaceVehicles(id, ids).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "围栏不存在"));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<Geofence> setEnabled(@PathVariable long id, @RequestBody EnabledRequest body) {
        if (body == null || body.enabled() == null) throw new IllegalArgumentException("enabled 不能为空");
        return geofences.setEnabled(id, body.enabled()).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "围栏不存在"));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        return geofences.delete(id) ? ApiResponse.ok(null) : ApiResponse.error("4004", "围栏不存在");
    }

    public record VehicleAssignments(List<String> deviceIds) {}
    public record EnabledRequest(Boolean enabled) {}

    public record GeofenceRequest(
            String name,
            Double centerGcjLat,
            Double centerGcjLng,
            Double radiusMeters,
            String color,
            Boolean enabled,
            Boolean alertOnEnter,
            Boolean alertOnExit,
            Double speedLimitKph,
            List<String> vehicleIds) {

        Geofence toDomain() {
            if (centerGcjLat == null || centerGcjLng == null || radiusMeters == null) {
                throw new IllegalArgumentException("围栏坐标和半径不能为空");
            }
            return new Geofence(null, name, centerGcjLat, centerGcjLng, radiusMeters,
                    color, Boolean.TRUE.equals(enabled), Boolean.TRUE.equals(alertOnEnter),
                    Boolean.TRUE.equals(alertOnExit), speedLimitKph, vehicleIds, 0, null, null);
        }
    }
}
