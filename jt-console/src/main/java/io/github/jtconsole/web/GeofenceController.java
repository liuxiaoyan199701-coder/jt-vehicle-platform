package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.Geofence;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.operations.GeofenceService;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
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
    @RequirePermission(Permissions.GEOFENCE_LIST)
    public ApiResponse<List<Geofence>> list(DataScope scope) {
        return ApiResponse.ok(geofences.findAll(scope));
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.GEOFENCE_LIST)
    public ApiResponse<Geofence> get(@PathVariable long id, DataScope scope) {
        return geofences.findById(id, scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "围栏不存在"));
    }

    @PostMapping
    @RequirePermission(Permissions.GEOFENCE_MANAGE)
    @Audited(value = "新增电子围栏", resourceType = "geofence")
    public ApiResponse<Geofence> create(
            @RequestBody GeofenceRequest body, AuthorizedPrincipal principal) {
        return ApiResponse.ok(geofences.create(principal, body.toDomain()));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.GEOFENCE_MANAGE)
    @Audited(value = "编辑电子围栏", resourceType = "geofence")
    public ApiResponse<Geofence> update(
            @PathVariable long id, @RequestBody GeofenceRequest body, DataScope scope) {
        return geofences.update(id, body.toDomain(), scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "围栏不存在"));
    }

    @PutMapping("/{id}/vehicles")
    @RequirePermission(Permissions.GEOFENCE_MANAGE)
    @Audited(value = "调整围栏车辆", resourceType = "geofence")
    public ApiResponse<Geofence> replaceVehicles(
            @PathVariable long id, @RequestBody VehicleAssignments body, DataScope scope) {
        List<String> ids = body == null ? null : body.deviceIds();
        return geofences.replaceVehicles(id, ids, scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "围栏不存在"));
    }

    @PutMapping("/{id}/enabled")
    @RequirePermission(Permissions.GEOFENCE_MANAGE)
    @Audited(value = "启停电子围栏", resourceType = "geofence")
    public ApiResponse<Geofence> setEnabled(
            @PathVariable long id, @RequestBody EnabledRequest body, DataScope scope) {
        if (body == null || body.enabled() == null) throw new IllegalArgumentException("enabled 不能为空");
        return geofences.setEnabled(id, body.enabled(), scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "围栏不存在"));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.GEOFENCE_MANAGE)
    @Audited(value = "删除电子围栏", resourceType = "geofence")
    public ApiResponse<Void> delete(@PathVariable long id, DataScope scope) {
        return geofences.delete(id, scope)
                ? ApiResponse.ok(null) : ApiResponse.error("4004", "围栏不存在");
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
            List<String> vehicleIds,
            Long tenantId) {

        Geofence toDomain() {
            if (centerGcjLat == null || centerGcjLng == null || radiusMeters == null) {
                throw new IllegalArgumentException("围栏坐标和半径不能为空");
            }
            return new Geofence(null, name, centerGcjLat, centerGcjLng, radiusMeters,
                    color, Boolean.TRUE.equals(enabled), Boolean.TRUE.equals(alertOnEnter),
                    Boolean.TRUE.equals(alertOnExit), speedLimitKph, vehicleIds, 0,
                    tenantId, null, null);
        }
    }
}
