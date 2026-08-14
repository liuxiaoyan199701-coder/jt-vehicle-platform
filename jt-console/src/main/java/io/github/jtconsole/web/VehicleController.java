package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.operations.VehicleService.VehicleRequest;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicles;

    public VehicleController(VehicleService vehicles) {
        this.vehicles = vehicles;
    }

    @GetMapping
    @RequirePermission(Permissions.VEHICLE_LIST)
    public ApiResponse<List<Vehicle>> list(DataScope scope) {
        return ApiResponse.ok(vehicles.list(scope));
    }

    @GetMapping("/{deviceId}")
    @RequirePermission(Permissions.VEHICLE_LIST)
    public ApiResponse<Vehicle> get(@PathVariable String deviceId, DataScope scope) {
        return ApiResponse.ok(vehicles.get(deviceId, scope));
    }

    @PostMapping
    @RequirePermission(Permissions.VEHICLE_CREATE)
    @Audited(value = "新增车辆建档", resourceType = "vehicle")
    public ApiResponse<Vehicle> create(
            @RequestBody VehicleRequest body, AuthorizedPrincipal principal) {
        return ApiResponse.ok(vehicles.create(principal, body));
    }

    @PutMapping("/{deviceId}")
    @RequirePermission(Permissions.VEHICLE_UPDATE)
    @Audited(value = "编辑车辆档案", resourceType = "vehicle")
    public ApiResponse<Vehicle> update(
            @PathVariable String deviceId,
            @RequestBody VehicleRequest body,
            AuthorizedPrincipal principal) {
        return ApiResponse.ok(vehicles.update(principal, deviceId, body));
    }

    @DeleteMapping("/{deviceId}")
    @RequirePermission(Permissions.VEHICLE_DELETE)
    @Audited(value = "删除车辆档案", resourceType = "vehicle")
    public ApiResponse<Void> delete(
            @PathVariable String deviceId, AuthorizedPrincipal principal) {
        vehicles.delete(principal, deviceId);
        return ApiResponse.ok(null);
    }

    /**
     * 跨租户调拨。只有平台管理员可用——调拨会让该设备的全部历史数据随归属转移可见性。
     */
    @PostMapping("/{deviceId}/tenant")
    @RequirePermission(Permissions.PLATFORM_TENANT_MANAGE)
    @Audited(value = "跨租户调拨车辆", resourceType = "vehicle")
    public ApiResponse<Vehicle> reassign(
            @PathVariable String deviceId,
            @RequestBody Map<String, Long> body,
            AuthorizedPrincipal principal) {
        Long targetTenantId = body == null ? null : body.get("tenantId");
        if (targetTenantId == null) {
            throw new IllegalArgumentException("缺少目标租户");
        }
        return ApiResponse.ok(vehicles.reassign(principal, deviceId, targetTenantId));
    }
}
