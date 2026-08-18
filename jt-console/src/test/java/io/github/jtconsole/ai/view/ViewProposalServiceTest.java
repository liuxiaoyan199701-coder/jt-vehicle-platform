package io.github.jtconsole.ai.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.tool.ToolSession;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.support.TestPrincipals;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 视图提议的四道闸门。
 *
 * <p>用例重点全在**拒绝**上：能展示什么不容易出错，出错的一定是「本不该展示的被放过去了」。
 *
 * <p>视图是只读的，但校验强度不比动作低——**动作卡片还有「用户点确认」那一关，用户会看一眼参数；
 * 视图是自动就去取数的，中间没有人看**。
 */
class ViewProposalServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final VehicleService vehicles = Mockito.mock(VehicleService.class);
    private final ViewProposalService service = new ViewProposalService(vehicles);

    private ToolSession sessionFor(AuthorizedPrincipal principal) {
        return sessionFor(principal, new ViewBudget());
    }

    private ToolSession sessionFor(AuthorizedPrincipal principal, ViewBudget budget) {
        return new ToolSession(principal, principal.scope(), ZONE,
                AgentEventSink.noop(), ConfirmationPolicy.confirmEverything(), budget);
    }

    @Test
    void rejectsAViewTypeOutsideTheWhitelist() {
        ViewProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.platform()), "iframe", "打开一个网页", Map.of());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("不支持的视图类型");
    }

    /**
     * 没权限时**不产生提议**——产生了就意味着界面上会出现一块点了必然失败的内容，
     * 用户会反复点然后认为平台坏了。
     */
    @Test
    void rejectsWhenTheUserCannotViewThatKindOfContent() {
        ViewProposalService.Outcome outcome = service.propose(
                sessionFor(without(TestPrincipals.tenantAdmin(7L, 42L), Permissions.MONITOR_VIEW)),
                "live_map", "看看在哪", Map.of());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("没有查看");
    }

    /** 就地摘掉一个权限，而不是往共享夹具里加一个只有本文件用得上的构造器。 */
    private static AuthorizedPrincipal without(AuthorizedPrincipal principal, String permission) {
        Set<String> remaining = new HashSet<>(principal.permissions());
        remaining.remove(permission);
        return new AuthorizedPrincipal(
                principal.accountId(), principal.username(), principal.displayName(),
                principal.tenantId(), principal.tenantName(), principal.platform(),
                remaining, principal.roles(), principal.scope());
    }

    @Test
    void rejectsADeviceOutsideTheUsersDataScope() {
        Mockito.when(vehicles.get(Mockito.eq("999"), Mockito.any()))
                .thenThrow(new IllegalArgumentException("看不到"));

        ViewProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.tenantAdmin(7L, 42L)),
                "live_map", "看看在哪", Map.of("deviceId", "999"));

        assertThat(outcome.accepted()).isFalse();
        // 与「不存在」同一句话，不透露这台车属于别的租户。
        assertThat(outcome.message()).contains("未找到设备号为 999 的车辆");
    }

    @Test
    void acceptsAVisibleDevice() {
        Mockito.when(vehicles.get(Mockito.eq("138000000000"), Mockito.any()))
                .thenReturn(Mockito.mock(Vehicle.class));

        ViewProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.tenantAdmin(7L, 42L)),
                "live_map", "粤B12345 当前位置", Map.of("deviceId", "138000000000"));

        assertThat(outcome.accepted()).isTrue();
        ViewProposal proposal = outcome.proposal();
        assertThat(proposal.type()).isEqualTo(ViewType.LIVE_MAP);
        assertThat(proposal.title()).isEqualTo("粤B12345 当前位置");
        assertThat(proposal.viewId()).startsWith("v_");
        assertThat(proposal.params()).containsEntry("deviceId", "138000000000");
        // 事件里给出的是权限码与呈现方式，绝不含接口地址。
        assertThat(proposal.asEventData())
                .containsEntry("requiredPermission", "monitor:view")
                .containsEntry("presentation", "inline");
        assertThat(proposal.asEventData().toString()).doesNotContain("/api");
    }

    /** 留空设备号表示「全部在线车辆」，那本来就受数据范围约束，没有单独的目标可校验。 */
    @Test
    void acceptsAnOmittedDeviceIdAsAllOnlineVehicles() {
        ViewProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.tenantAdmin(7L, 42L)), "live_map", "都在哪", Map.of());

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.proposal().params()).doesNotContainKey("deviceId");
        Mockito.verify(vehicles, Mockito.never()).get(Mockito.anyString(), Mockito.any());
    }

    /**
     * 未知字段要明确回告而不是默默丢弃：把 deviceId 写成 device 时静默丢弃会渲染出一张
     * 「全部车辆」的地图，而用户以为看的是那一台——错得没有任何提示。
     */
    @Test
    void rejectsUnknownParametersInsteadOfDroppingThem() {
        ViewProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.tenantAdmin(7L, 42L)),
                "live_map", "看看", Map.of("device", "138000000000"));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("不接受参数 device");
        assertThat(outcome.message()).contains("deviceId");
    }

    @Test
    void refusesMoreViewsThanTheTurnBudgetAllows() {
        Mockito.when(vehicles.get(Mockito.anyString(), Mockito.any()))
                .thenReturn(Mockito.mock(Vehicle.class));
        ViewBudget budget = new ViewBudget(2);
        ToolSession session = sessionFor(TestPrincipals.tenantAdmin(7L, 42L), budget);

        // 三台**不同**的车，签名各不相同，所以拦住第三张的是配额而不是去重。
        assertThat(service.propose(session, "live_map", "甲", Map.of("deviceId", "A")).accepted())
                .isTrue();
        assertThat(service.propose(session, "live_map", "乙", Map.of("deviceId", "B")).accepted())
                .isTrue();
        ViewProposalService.Outcome third =
                service.propose(session, "live_map", "丙", Map.of("deviceId", "C"));

        assertThat(third.accepted()).isFalse();
        assertThat(third.message()).contains("上限");
    }

    /**
     * 同一台车的同类视图一轮只出一张。
     *
     * <p>线上实测：用户问一句「看看昨天的轨迹」，模型把时间窗层层收窄查了四次，每次都触发出图，
     * 对话里堆了四张同一段行程的嵌套地图。那是模型在探索，不是用户想要四张。
     */
    @Test
    void showsTheSameTargetOnlyOncePerTurn() {
        Mockito.when(vehicles.get(Mockito.anyString(), Mockito.any()))
                .thenReturn(Mockito.mock(Vehicle.class));
        ViewBudget budget = new ViewBudget(4);
        ToolSession session = sessionFor(TestPrincipals.tenantAdmin(7L, 42L), budget);

        Map<String, Object> wide = Map.of("deviceId", "A",
                "start", "2026-08-17T00:00:00", "end", "2026-08-17T23:00:00");
        Map<String, Object> narrow = Map.of("deviceId", "A",
                "start", "2026-08-17T03:00:00", "end", "2026-08-17T06:00:00");

        assertThat(service.propose(session, "track_map", "全天", wide).accepted()).isTrue();
        // 时间窗不同，但对用户来说是同一段行程的同一张图。
        assertThat(service.propose(session, "track_map", "缩窄", narrow).accepted()).isFalse();
        // 换一台车照常放行。
        assertThat(service.propose(session, "track_map", "另一台",
                Map.of("deviceId", "B", "start", "2026-08-17T00:00:00",
                        "end", "2026-08-17T06:00:00")).accepted()).isTrue();
        assertThat(budget.used()).isEqualTo(2);
    }

    /** 被拒绝的提议不该消耗名额，否则模型改对参数重试几次就把额度耗光了。 */
    @Test
    void rejectedProposalsDoNotConsumeBudget() {
        ViewBudget budget = new ViewBudget(1);
        ToolSession session = sessionFor(TestPrincipals.tenantAdmin(7L, 42L), budget);

        service.propose(session, "iframe", "非法类型", Map.of());
        service.propose(session, "live_map", "未知字段", Map.of("device", "x"));

        assertThat(budget.used()).isZero();
        assertThat(service.propose(session, "live_map", "这一张该成功", Map.of()).accepted()).isTrue();
    }
}
