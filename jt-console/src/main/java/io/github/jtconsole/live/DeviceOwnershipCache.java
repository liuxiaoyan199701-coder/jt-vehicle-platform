package io.github.jtconsole.live;

import io.github.jtconsole.migration.SchemaMigrationRunner;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.repository.VehicleRepository.DeviceOwnership;
import io.github.jtconsole.security.DataScope;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 「设备 → 租户/部门」的进程内映射，供实时广播按会话数据范围过滤。
 *
 * <p>位置更新每秒可达数百条，分发线程不能每条去查库；而建档、调拨、改部门都在同一个服务方法内
 * 同步更新本缓存，不一致窗口是毫秒级。最坏后果是一条位置更新推错范围，
 * 下一次走数据库的校准查询就会纠正。
 */
@Component
public class DeviceOwnershipCache implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceOwnershipCache.class);

    private final VehicleRepository vehicles;
    private final ConcurrentHashMap<String, Ownership> byDevice = new ConcurrentHashMap<>();

    public DeviceOwnershipCache(VehicleRepository vehicles, SchemaMigrationRunner migrations) {
        this.vehicles = vehicles;
        // 仅用于强制 bean 创建顺序：归属列必须先补齐才能装载。
        migrations.currentVersion();
    }

    @Override
    public void afterPropertiesSet() {
        reload();
    }

    public void reload() {
        byDevice.clear();
        for (DeviceOwnership record : vehicles.findAllOwnerships()) {
            if (record.tenantId() != null) {
                byDevice.put(record.deviceId(),
                        new Ownership(record.tenantId(), record.departmentId()));
            }
        }
        LOGGER.info("已装载 {} 台已建档设备的归属映射", byDevice.size());
    }

    public void put(String deviceId, Long tenantId, Long departmentId) {
        if (tenantId == null) {
            byDevice.remove(deviceId);
            return;
        }
        byDevice.put(deviceId, new Ownership(tenantId, departmentId));
    }

    public void remove(String deviceId) {
        byDevice.remove(deviceId);
    }

    public Optional<Ownership> find(String deviceId) {
        return Optional.ofNullable(byDevice.get(deviceId));
    }

    /**
     * 该设备的更新是否应推送给持有此范围的会话。
     *
     * <p>未建档设备只推给平台管理员：租户用户的世界里只有本租户已建档车辆。
     */
    public boolean visibleTo(String deviceId, DataScope scope) {
        Ownership owner = byDevice.get(deviceId);
        if (owner == null) {
            return scope.isPlatform() && scope.tenantId() == null;
        }
        if (scope.isPlatform() && scope.tenantId() == null) {
            return true;
        }
        if (scope.empty()) {
            return false;
        }
        Long scopeTenant = scope.tenantId();
        if (scopeTenant != null && !scopeTenant.equals(owner.tenantId())) {
            return false;
        }
        if (!scope.departmentRestricted()) {
            return true;
        }
        return owner.departmentId() != null
                && scope.visibleDepartmentIds().contains(owner.departmentId());
    }

    public int size() {
        return byDevice.size();
    }

    /** 设备当前归属。 */
    public record Ownership(long tenantId, Long departmentId) {}
}
