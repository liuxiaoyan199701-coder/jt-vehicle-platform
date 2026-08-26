package io.github.jtconsole.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.ai.briefing.BriefingItem;
import io.github.jtconsole.ai.briefing.DashboardFinding;
import io.github.jtconsole.repository.VehicleRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 可见范围的唯一一份判定。
 *
 * <p>首页要点与通知铃铛调用的是这同一段代码，所以「同一条发现两处可见性一致」
 * 由结构保证而不是靠两边写得一样。本类覆盖这段判定本身；
 * 两条读取路径端到端确实一致，另由 {@code NoticeControllerTest} 断言。
 */
class ScopeVisibilityTest {

    private static final long TENANT = 1L;

    @Test
    void aTenantWideCallerSeesEverythingIncludingAggregateConclusions() {
        ScopeVisibility.Filter filter = filterFor(DataScope.tenantWide(TENANT), Set.of());

        assertThat(filter.visible(List.of())).isTrue();
        assertThat(filter.visible(List.of("device-1", "device-99"))).isTrue();
    }

    @Test
    void platformAdministratorsAreNeverNarrowedByDepartment() {
        ScopeVisibility.Filter filter = filterFor(DataScope.platform(), Set.of());

        assertThat(filter.visible(List.of())).isTrue();
        assertThat(filter.visible(List.of("unarchived-device"))).isTrue();
    }

    /** 引用了范围外车辆的整条丢弃：删掉几个设备号并不会让那句话变准确。 */
    @Test
    void aConclusionTouchingOneOutOfScopeVehicleIsDroppedWhole() {
        ScopeVisibility.Filter filter = filterFor(
                DataScope.departments(TENANT, Set.of(7L)), Set.of("device-1", "device-2"));

        assertThat(filter.visible(List.of("device-1"))).isTrue();
        assertThat(filter.visible(List.of("device-1", "device-2"))).isTrue();
        assertThat(filter.visible(List.of("device-1", "device-3"))).isFalse();
        assertThat(filter.visible(List.of("device-3"))).isFalse();
    }

    /** 「今日告警 47 条」这句话本身就泄露了范围外的信息，无法部分过滤。 */
    @Test
    void aggregateConclusionsAreWithheldFromPartialScopesEntirely() {
        ScopeVisibility.Filter filter = filterFor(
                DataScope.departments(TENANT, Set.of(7L)), Set.of("device-1"));

        assertThat(filter.visible(List.of())).isFalse();
        assertThat(filter.visible(null)).isFalse();
    }

    @Test
    void anAccountWithNoDepartmentsSeesNothingAtAll() {
        ScopeVisibility.Filter filter = filterFor(DataScope.departments(TENANT, Set.of()), Set.of());

        assertThat(filter.visible(List.of())).isFalse();
        assertThat(filter.visible(List.of("device-1"))).isFalse();
    }

    /**
     * 「聚合」这件事，判定这一侧与要点那一侧必须是同一个意思。
     *
     * <p>{@code BriefingItem.aggregate()} 与本判定各自决定「什么算租户级聚合结论」。
     * 两者一旦分叉，就会出现「要点认为它涉及具体车辆、判定认为它是聚合」这种半可见状态，
     * 而那正是「铃铛里有但首页要点里没有」的来源。这里逐个形态断言两侧的口径一致。
     */
    @Test
    void whatCountsAsAnAggregateConclusionMeansTheSameThingOnBothSides() {
        ScopeVisibility.Filter partial = filterFor(
                DataScope.departments(TENANT, Set.of(7L)), Set.of("device-1"));

        for (List<String> deviceIds : new java.util.ArrayList<>(java.util.Arrays.asList(
                null, List.<String>of(), List.of("device-1"), List.of("device-9")))) {
            BriefingItem item = new BriefingItem(
                    "finding", DashboardFinding.Category.OFFLINE,
                    DashboardFinding.Severity.WARN, "一句话", java.util.Map.of(), deviceIds, null);
            // 要点侧认定为聚合的，判定这一侧必须一条也不给部分范围。
            assertThat(item.aggregate())
                    .as("聚合口径：%s", deviceIds)
                    .isEqualTo(deviceIds == null || deviceIds.isEmpty());
            if (item.aggregate()) {
                assertThat(partial.visible(deviceIds)).isFalse();
            }
        }
    }

    private static ScopeVisibility.Filter filterFor(DataScope scope, Set<String> visibleDevices) {
        VehicleRepository vehicles = mock(VehicleRepository.class);
        when(vehicles.visibleDeviceIds(any())).thenReturn(visibleDevices);
        return new ScopeVisibility(vehicles).forScope(scope);
    }
}
