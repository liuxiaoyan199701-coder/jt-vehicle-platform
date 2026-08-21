package io.github.jtconsole.ai.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.tool.ToolSession;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.support.TestPrincipals;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 司机建档/修改动作的白名单与确认语义。 */
class DriverActionProposalTest {

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
    void driverCreateMissingRequiredFieldsIsRejected() {
        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.tenantAdmin(7L, 42L)),
                "driver_create", "建档", null,
                Map.of("name", "张三"), ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("idCard").contains("licenseNo");
    }

    @Test
    void driverCreateAlwaysRequiresConfirmation() {
        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.tenantAdmin(7L, 42L)),
                "driver_create", "建档", null,
                Map.of("name", "张三", "idCard", "110101199001011234", "licenseNo", "LIC-1"),
                ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.proposal().type()).isEqualTo(ActionType.DRIVER_CREATE);
        // 身份证号等敏感个人数据，永远需要人工确认，不随配置放宽。
        assertThat(outcome.proposal().requiresConfirmation()).isTrue();
    }

    @Test
    void aViewerWithoutDriverManageCannotProposeDriverCreate() {
        ActionProposalService.Outcome outcome = service.propose(
                sessionFor(TestPrincipals.viewer(9L, 42L)),
                "driver_create", "建档", null,
                Map.of("name", "张三", "idCard", "110101199001011234", "licenseNo", "LIC-1"),
                ConfirmationPolicy.confirmEverything());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.message()).contains("没有执行");
    }
}
