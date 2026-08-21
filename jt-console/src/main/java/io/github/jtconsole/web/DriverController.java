package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.Driver;
import io.github.jtconsole.domain.DriverIdentityEvent;
import io.github.jtconsole.domain.DriverSession;
import io.github.jtconsole.operations.DriverService;
import io.github.jtconsole.operations.VehicleService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DriverController {

    private final DriverService drivers;
    private final VehicleService vehicles;

    public DriverController(DriverService drivers, VehicleService vehicles) {
        this.drivers = drivers;
        this.vehicles = vehicles;
    }

    // ---------------- 司机档案 ----------------

    @GetMapping("/api/drivers")
    @RequirePermission(Permissions.DRIVER_LIST)
    public ApiResponse<DriverPage> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            DataScope scope, AuthorizedPrincipal principal) {
        DriverService.DriverPage result = drivers.search(
                keyword, departmentId, scope, page, Math.min(pageSize, 100));
        List<Driver> masked = result.items().stream()
                .map(item -> mask(item, principal)).toList();
        return ApiResponse.ok(new DriverPage(masked, result.total()));
    }

    @GetMapping("/api/drivers/{id}")
    @RequirePermission(Permissions.DRIVER_LIST)
    public ApiResponse<Driver> get(@PathVariable long id, DataScope scope,
                                   AuthorizedPrincipal principal) {
        return drivers.findById(id, scope).map(item -> ApiResponse.ok(mask(item, principal)))
                .orElseGet(() -> ApiResponse.error("4004", "司机不存在"));
    }

    @PostMapping("/api/drivers")
    @RequirePermission(Permissions.DRIVER_MANAGE)
    @Audited(value = "新增司机档案", resourceType = "driver")
    public ApiResponse<Driver> create(@RequestBody DriverRequest body, AuthorizedPrincipal principal) {
        return ApiResponse.ok(drivers.create(principal, body.toDomain()));
    }

    @PutMapping("/api/drivers/{id}")
    @RequirePermission(Permissions.DRIVER_MANAGE)
    @Audited(value = "编辑司机档案", resourceType = "driver")
    public ApiResponse<Driver> update(@PathVariable long id, @RequestBody DriverRequest body,
                                      DataScope scope) {
        return drivers.update(id, body.toDomain(), scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "司机不存在"));
    }

    @DeleteMapping("/api/drivers/{id}")
    @RequirePermission(Permissions.DRIVER_MANAGE)
    @Audited(value = "删除司机档案", resourceType = "driver")
    public ApiResponse<Void> delete(@PathVariable long id, DataScope scope) {
        return drivers.delete(id, scope)
                ? ApiResponse.ok(null) : ApiResponse.error("4004", "司机不存在");
    }

    @GetMapping("/api/drivers/{id}/sessions")
    @RequirePermission(Permissions.DRIVER_LIST)
    public ApiResponse<List<DriverSession>> sessions(@PathVariable long id, DataScope scope) {
        return ApiResponse.ok(drivers.sessions(id, scope));
    }

    // ---------------- 身份事件 ----------------

    @GetMapping("/api/drivers/identity-events")
    @RequirePermission(Permissions.DRIVER_LIST)
    public ApiResponse<List<DriverIdentityEvent>> identityEvents(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) Boolean unmatched,
            @RequestParam(required = false) Boolean failed,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            DataScope scope) {
        return ApiResponse.ok(drivers.identityEvents(
                deviceId, unmatched, failed, start, end, scope, page, Math.min(pageSize, 100)));
    }

    // ---------------- 车辆当前驾驶员 ----------------

    @GetMapping("/api/vehicles/{deviceId}/driver")
    @RequirePermission(Permissions.DRIVER_LIST)
    public ApiResponse<CurrentDriver> currentDriver(
            @PathVariable String deviceId, DataScope scope, AuthorizedPrincipal principal) {
        String canonicalId = vehicles.requireVisibleDevice(deviceId, scope);
        return drivers.currentSession(canonicalId)
                .map(session -> ApiResponse.ok(toCurrent(session, scope, principal)))
                .orElseGet(() -> ApiResponse.ok(null));
    }

    @PostMapping("/api/vehicles/{deviceId}/driver")
    @RequirePermission(Permissions.DRIVER_MANAGE)
    @Audited(value = "手动绑定驾驶员", resourceType = "vehicle")
    public ApiResponse<DriverSession> bind(
            @PathVariable String deviceId, @RequestBody BindRequest body, DataScope scope) {
        String canonicalId = vehicles.requireVisibleDevice(deviceId, scope);
        if (body == null || body.driverId() == null) throw new IllegalArgumentException("driverId 不能为空");
        return ApiResponse.ok(drivers.manualBind(canonicalId, body.driverId(), scope));
    }

    @DeleteMapping("/api/vehicles/{deviceId}/driver")
    @RequirePermission(Permissions.DRIVER_MANAGE)
    @Audited(value = "手动解绑驾驶员", resourceType = "vehicle")
    public ApiResponse<Void> unbind(@PathVariable String deviceId, DataScope scope) {
        vehicles.requireVisibleDevice(deviceId, scope);
        drivers.manualUnbind(deviceId.trim());
        return ApiResponse.ok(null);
    }

    private Driver mask(Driver driver, AuthorizedPrincipal principal) {
        if (principal.hasPermission(Permissions.DRIVER_MANAGE)) {
            return driver;
        }
        return new Driver(driver.id(), driver.name(), driver.maskedIdCard(), driver.licenseNo(),
                driver.institution(), driver.licenseValidPeriod(), driver.phone(), driver.remark(),
                driver.departmentId(), driver.tenantId(), driver.createdAt(), driver.updatedAt());
    }

    private CurrentDriver toCurrent(DriverSession session, DataScope scope, AuthorizedPrincipal principal) {
        Driver driver = session.driverId() == null
                ? null
                : drivers.findById(session.driverId(), scope)
                        .map(item -> mask(item, principal)).orElse(null);
        return new CurrentDriver(session.deviceId(), session.driverId(),
                driver == null ? session.driverName() : driver.name(),
                session.licenseNo(), session.startedAt(), session.source());
    }

    public record DriverPage(List<Driver> items, long total) {
    }

    public record BindRequest(Long driverId) {
    }

    public record CurrentDriver(
            String deviceId, Long driverId, String driverName, String licenseNo,
            String startedAt, String source) {
    }

    public record DriverRequest(
            String name,
            String idCard,
            String licenseNo,
            String institution,
            String licenseValidPeriod,
            String phone,
            String remark,
            Long departmentId,
            Long tenantId) {

        Driver toDomain() {
            return new Driver(null, name, idCard, licenseNo, institution, licenseValidPeriod,
                    phone, remark, departmentId, tenantId, null, null);
        }
    }
}
