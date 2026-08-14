package io.github.jtconsole.gateway;

import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.repository.TenantRepository;
import io.github.jtconsole.repository.VehicleRepository;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 jt-signal 查询设备档案的接口。
 *
 * <p>只回答事实（这台设备建过档吗、属于哪个租户、该租户当前可用吗），不回答「能否接入」。
 * 判定留在网关侧是既定架构约定，也是控制台不可达时还能 fail-open 保联通的前提——
 * 把判定挪到这里，控制台一抖动就会变成设备批量掉线。
 *
 * <p>未建档设备返回 404：网关的档案客户端把 404 解释为「查无此档」，再按自己的
 * {@code unregistered-device-policy} 决定放行还是拒绝。
 */
@RestController
@RequestMapping(DeviceRegistryKeyFilter.REGISTRY_PATH)
public class DeviceRegistryController {

    private final VehicleRepository vehicles;
    private final TenantRepository tenants;
    private final Clock clock;

    @Autowired
    public DeviceRegistryController(VehicleRepository vehicles, TenantRepository tenants) {
        this(vehicles, tenants, Clock.systemUTC());
    }

    DeviceRegistryController(
            VehicleRepository vehicles, TenantRepository tenants, Clock clock) {
        this.vehicles = vehicles;
        this.tenants = tenants;
        this.clock = clock;
    }

    /**
     * @param terminalId 网关按 {@code terminalId} 查询；同时接受 {@code deviceId} 便于人工排查
     */
    @GetMapping
    public ResponseEntity<DeviceRegistryEntry> lookup(
            @RequestParam(value = "terminalId", required = false) String terminalId,
            @RequestParam(value = "deviceId", required = false) String deviceId) {
        String requested = firstNonBlank(terminalId, deviceId);
        if (requested == null) {
            return ResponseEntity.notFound().build();
        }
        Optional<Vehicle> vehicle = vehicles.findByIdUnscoped(requested);
        if (vehicle.isEmpty() || vehicle.get().tenantId() == null) {
            return ResponseEntity.notFound().build();
        }

        Vehicle archived = vehicle.get();
        return tenants.findById(archived.tenantId())
                .map(tenant -> ResponseEntity.ok(new DeviceRegistryEntry(
                        requested,
                        archived.deviceId(),
                        archived.deviceId(),
                        archived.plateNo(),
                        tenant.code(),
                        tenant.active(clock.instant()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    /**
     * 字段名与网关的档案客户端一一对应。
     *
     * <p>刻意不含 {@code allowed}/{@code authorized}/{@code decision} 这类字段——
     * 网关的客户端见到它们会直接判定响应非法，这是双方共同守住「事实与判定分离」的护栏。
     *
     * @param tenantActive 租户是否可用，已合并停用与到期判定；这是事实，不是判定结论
     */
    public record DeviceRegistryEntry(
            String terminalId,
            String deviceId,
            String mobileNo,
            String plateNo,
            String tenantCode,
            boolean tenantActive) {
    }
}
