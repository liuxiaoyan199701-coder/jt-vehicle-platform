package io.github.jtconsole.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.migration.SchemaMigrationRunner;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.repository.VehicleRepository.DeviceOwnership;
import io.github.jtconsole.security.DataScope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 广播过滤的判定表。
 *
 * <p>这是实时推送唯一的隔离屏障：分发线程每秒可能处理数百条位置更新，来不及查库，
 * 因此判定完全依赖这份内存映射的正确性。
 */
class DeviceOwnershipCacheTest {

    private static final String TENANT_A_DEVICE = "13800000001";
    private static final String TENANT_A_DEPT_DEVICE = "13800000002";
    private static final String TENANT_B_DEVICE = "13800000003";
    private static final String UNARCHIVED_DEVICE = "13800009999";

    private DeviceOwnershipCache cache;

    @BeforeEach
    void loadOwnerships() {
        VehicleRepository vehicles = mock(VehicleRepository.class);
        when(vehicles.findAllOwnerships()).thenReturn(List.of(
                new DeviceOwnership(TENANT_A_DEVICE, 1L, null),
                new DeviceOwnership(TENANT_A_DEPT_DEVICE, 1L, 10L),
                new DeviceOwnership(TENANT_B_DEVICE, 2L, null)));
        cache = new DeviceOwnershipCache(vehicles, mock(SchemaMigrationRunner.class));
        cache.afterPropertiesSet();
    }

    @Test
    void tenantSessionsOnlySeeTheirOwnDevices() {
        DataScope tenantA = DataScope.tenantWide(1L);
        DataScope tenantB = DataScope.tenantWide(2L);

        assertThat(cache.visibleTo(TENANT_A_DEVICE, tenantA)).isTrue();
        assertThat(cache.visibleTo(TENANT_B_DEVICE, tenantA)).isFalse();
        assertThat(cache.visibleTo(TENANT_B_DEVICE, tenantB)).isTrue();
        assertThat(cache.visibleTo(TENANT_A_DEVICE, tenantB)).isFalse();
    }

    @Test
    void unarchivedDevicesReachPlatformSessionsOnly() {
        assertThat(cache.visibleTo(UNARCHIVED_DEVICE, DataScope.platform())).isTrue();
        assertThat(cache.visibleTo(UNARCHIVED_DEVICE, DataScope.tenantWide(1L))).isFalse();
        // 平台管理员按租户筛选时，未建档设备不属于任何租户，因此也不该出现
        assertThat(cache.visibleTo(UNARCHIVED_DEVICE, DataScope.platformFilteredBy(1L))).isFalse();
    }

    @Test
    void departmentScopeExcludesVehiclesWithNoDepartment() {
        DataScope department = DataScope.departments(1L, Set.of(10L));

        assertThat(cache.visibleTo(TENANT_A_DEPT_DEVICE, department)).isTrue();
        // 未分配部门的车辆只对「本租户全部」可见——宁可少看到，不可越权看到
        assertThat(cache.visibleTo(TENANT_A_DEVICE, department)).isFalse();
    }

    @Test
    void emptyDepartmentScopeSeesNothing() {
        DataScope nothing = DataScope.departments(1L, Set.of());

        assertThat(cache.visibleTo(TENANT_A_DEVICE, nothing)).isFalse();
        assertThat(cache.visibleTo(TENANT_A_DEPT_DEVICE, nothing)).isFalse();
        assertThat(cache.visibleTo(UNARCHIVED_DEVICE, nothing)).isFalse();
    }

    @Test
    void transferAndDepartmentChangeTakeEffectOnTheNextUpdate() {
        cache.put(TENANT_A_DEVICE, 2L, null);

        assertThat(cache.visibleTo(TENANT_A_DEVICE, DataScope.tenantWide(1L))).isFalse();
        assertThat(cache.visibleTo(TENANT_A_DEVICE, DataScope.tenantWide(2L))).isTrue();

        cache.put(TENANT_A_DEPT_DEVICE, 1L, 20L);
        assertThat(cache.visibleTo(TENANT_A_DEPT_DEVICE, DataScope.departments(1L, Set.of(10L)))).isFalse();
        assertThat(cache.visibleTo(TENANT_A_DEPT_DEVICE, DataScope.departments(1L, Set.of(20L)))).isTrue();
    }

    @Test
    void deletedVehiclesFallBackToUnarchived() {
        cache.remove(TENANT_A_DEVICE);

        assertThat(cache.visibleTo(TENANT_A_DEVICE, DataScope.tenantWide(1L))).isFalse();
        assertThat(cache.visibleTo(TENANT_A_DEVICE, DataScope.platform())).isTrue();
    }
}
