package io.github.jtconsole.ai.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.tool.ToolSession;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestPrincipals;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 动作提议的三道闸门：白名单、权限、数据范围。
 *
 * <p>这是整个写路径的把关处，所以用例的重点全在**拒绝**上：能提议什么反倒不容易出错，
 * 出错的一定是「本不该提议的被放过去了」。
 */
class ActionProposalServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final VehicleService vehicles = Mockito.mock(VehicleService.class);
    private final AlarmRepository alarms = Mockito.mock(AlarmRepository.class);
    private final ActionProposalService service = new ActionProposalService(vehicles, alarms);

    private ToolSession sessionFor(AuthorizedPrincipal principal) {
        return new ToolSession(principal, principal.scope(), ZONE,
                AgentEventSink.noop(), ConfirmationPolicy.confirmEverything(),
                new io.github.jtconsole.ai.view.ViewBudget(),
                new io.github.jtconsole.ai.tool.ToolRoundBudget(8));
    }

    @Test
    void anUnknownActionTypeIsRejected() {
        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.platform()),
                "drop_database", "删库", null, Map.of(), ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("不支持的动作类型");
    }

    @Test
    void aTenantUserCannotProposePlatformLevelActions() {
        // 租户管理员权限齐全，但平台级动作不在其权限集里。
        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.tenantAdmin(7L, 42L)),
                "tenant_create", "开一个租户", null,
                Map.of("name", "某某公司"), ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("没有执行");
        // 不能顺带告诉他平台侧有哪些他够不着的能力。
        assertThat(outcome.message()).doesNotContain("平台管理员");
    }

    @Test
    void aReadOnlyStyleAccountWithoutTheCodeCannotHandleAlarms() {
        AuthorizedPrincipal viewer = TestPrincipals.departmentOperator(9L, 42L, java.util.Set.of(1L));
        // 构造一个确实不含告警处置权限的账号
        AuthorizedPrincipal stripped = new AuthorizedPrincipal(
                viewer.accountId(), viewer.username(), viewer.displayName(), viewer.tenantId(),
                viewer.tenantName(), false, java.util.Set.of("vehicle:list"),
                List.of(), viewer.scope());

        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(stripped), "alarm_close", "关掉告警", null,
                Map.of("alarmId", 1L), ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("没有执行");
        // 权限没过就不该再去查告警，免得把「这条告警存在」透出去。
        Mockito.verifyNoInteractions(alarms);
    }

    @Test
    void anAlarmOutsideTheCallersScopeReadsAsSimplyNotFound() {
        AuthorizedPrincipal admin = TestPrincipals.tenantAdmin(7L, 42L);
        Mockito.when(alarms.findById(Mockito.eq(482L), Mockito.any(DataScope.class)))
                .thenReturn(Optional.empty());

        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(admin), "alarm_acknowledge", "确认告警", null,
                Map.of("alarmId", 482), ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isFalse();
        // 「无权查看」与「不存在」必须是同一句话，否则等于确认了它存在于别的租户。
        assertThat(outcome.message()).isEqualTo("未找到编号为 482 的告警。");
    }

    @Test
    void aVehicleOutsideTheCallersScopeCannotBeTargeted() {
        AuthorizedPrincipal admin = TestPrincipals.tenantAdmin(7L, 42L);
        Mockito.when(vehicles.get(Mockito.eq("13900000001"), Mockito.any(DataScope.class)))
                .thenThrow(IamException.notFound("车辆不存在"));

        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(admin), "vehicle_delete", "删掉这台车", null,
                Map.of("deviceId", "13900000001"), ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("未找到设备号");
    }

    @Test
    void aValidProposalCarriesTheParametersTheUserWillSee() {
        AuthorizedPrincipal admin = TestPrincipals.tenantAdmin(7L, 42L);

        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(admin), "vehicle_create", "建档粤B12345", "用户要求新增一台车",
                Map.of("deviceId", "13800138000", "plateNo", "粤B12345"),
                ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isTrue();
        ActionProposal proposal = outcome.proposal();
        assertThat(proposal.type()).isEqualTo(ActionType.VEHICLE_CREATE);
        // 参数必须原样带到界面：确认这一步的价值有一半是让用户看见车牌被听错了一个字。
        assertThat(proposal.params())
                .containsEntry("deviceId", "13800138000")
                .containsEntry("plateNo", "粤B12345");
        assertThat(proposal.asEventData()).containsEntry("requiresConfirmation", true);
        assertThat(proposal.proposalId()).isNotBlank();
    }

    @Test
    void configuredAutoExecutionShowsUpOnTheProposalButNeverForIrreversibleActions() {
        AuthorizedPrincipal admin = TestPrincipals.tenantAdmin(7L, 42L);
        ConfirmationPolicy relaxed = ConfirmationPolicy.autoExecutingTypes(
                java.util.Set.of(ActionType.VEHICLE_CREATE, ActionType.VEHICLE_DELETE));
        Mockito.when(vehicles.get(Mockito.anyString(), Mockito.any(DataScope.class)))
                .thenReturn(new Vehicle("13800138000", "粤B12345", "蓝色", "测试", 1,
                        null, 42L, null, "2026-08-17T00:00:00Z", "2026-08-17T00:00:00Z"));

        ActionProposalService.Outcome created = service.propose(
                sessionFor(admin), "vehicle_create", "建档", null,
                Map.of("deviceId", "13800138000", "plateNo", "粤B12345"), relaxed);
        ActionProposalService.Outcome deleted = service.propose(
                sessionFor(admin), "vehicle_delete", "删除", null,
                Map.of("deviceId", "13800138000"), relaxed);

        assertThat(created.proposal().requiresConfirmation()).isFalse();
        // 配了也不管用：删除永远要确认。
        assertThat(deleted.proposal().requiresConfirmation()).isTrue();
    }

    @Test
    void aTenantUserNeedNotSupplyTheTenantBecauseItComesFromTheirLogin() {
        // 租户用户建档时后端按登录态取归属，传了也忽略——所以这里不能反过来要求他必须填。
        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.tenantAdmin(7L, 42L)),
                "vehicle_create", "建档", null,
                Map.of("deviceId", "13800138000", "plateNo", "粤B12345"),
                ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.proposal().params()).doesNotContainKey("tenantId");
    }

    @Test
    void aPlatformAdminMustPickATenantBeforeTheCardIsEverShown() {
        // 平台管理员不属于任何租户，后端会拒绝无归属的建档。必须在提议阶段就拦下来——
        // 否则用户点了确认才收到「请先选择车辆所属租户」，那时候已经晚了。
        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.platform()),
                "vehicle_create", "建档", null,
                Map.of("deviceId", "13800138000", "plateNo", "粤B12345"),
                ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("tenantId").contains("list_tenants");
    }

    @Test
    void aTenantNameIsNotAcceptedWhereANumericIdIsRequired() {
        // 模型实测把 tenantId 填成过用户名 "admin"：名字对、值错，一路放行到点确认才炸。
        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.platform()),
                "vehicle_create", "建档", null,
                Map.of("deviceId", "13800138000", "plateNo", "粤B12345", "tenantId", "admin"),
                ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("tenantId");
    }

    @Test
    void aPlatformAdminWithANumericTenantPassesValidation() {
        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.platform()),
                "vehicle_create", "建档", null,
                Map.of("deviceId", "13800138000", "plateNo", "粤B12345", "tenantId", 1),
                ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.proposal().params()).containsEntry("tenantId", 1);
    }

    @Test
    void theActionCatalogIsTrimmedToWhatTheCallerCanActuallyDo() {
        List<ActionType> platformActions = service.availableTo(TestPrincipals.platform());
        List<ActionType> tenantActions = service.availableTo(TestPrincipals.tenantAdmin(7L, 42L));

        assertThat(platformActions).contains(ActionType.TENANT_CREATE, ActionType.PLAN_UPDATE);
        // 租户看不到平台级动作——不是「看得到但用不了」，是根本不出现在它的工具契约里。
        assertThat(tenantActions)
                .doesNotContain(ActionType.TENANT_CREATE, ActionType.TENANT_UPDATE,
                        ActionType.TENANT_DISABLE, ActionType.PLAN_CREATE, ActionType.PLAN_UPDATE)
                .contains(ActionType.VEHICLE_CREATE, ActionType.GEOFENCE_CREATE,
                        ActionType.ALARM_ACKNOWLEDGE);
    }

    /**
     * tenantId 的提示词曾写着「不确定就省略」，而平台管理员省略它必被拒——提示词把模型
     * 直接引到了死路上。这类错误在服务端测试里照不出来，只能在提示词本身钉住。
     */
    @Test
    void tenantIdHintDoesNotTellThePlatformAdministratorToOmitIt() {
        String hint = ActionType.fieldHint("tenantId");

        assertThat(hint).contains("平台管理员必填");
        assertThat(hint).doesNotContain("不确定就省略");
    }

    /**
     * 顶点写成对象必须当轮被拦下并回告格式。放行的代价是用户点一次确认才知道错，
     * 而那条后端报错不带格式说明——模型只会接着猜下一种对象写法。
     */
    @Test
    void geofenceVerticesWrittenAsObjectsAreRejectedWithTheExpectedFormat() {
        ToolSession session = sessionFor(TestPrincipals.platform());

        ActionProposalService.Outcome objectPairs = service.propose(session, "geofence_update",
                "改为四边形", null,
                Map.of("id", 2, "shape", "polygon", "points", List.of(
                        Map.of("lat", 22.643463, "lng", 114.030807),
                        Map.of("lat", 22.634454, "lng", 114.021051))),
                ConfirmationPolicy.confirmEverything());

        assertThat(objectPairs.accepted()).isFalse();
        assertThat(objectPairs.message())
                .contains("points")
                .contains("[lat,lng]")
                .contains("不要写成");

        ActionProposalService.Outcome coordinatePairs = service.propose(session, "geofence_update",
                "改为四边形", null,
                Map.of("id", 2, "shape", "polygon", "points", List.of(
                        List.of(22.643463, 114.030807), List.of(22.634454, 114.030807),
                        List.of(22.634454, 114.021051), List.of(22.643463, 114.021051))),
                ConfirmationPolicy.confirmEverything());

        assertThat(coordinatePairs.accepted()).isTrue();
    }
}
