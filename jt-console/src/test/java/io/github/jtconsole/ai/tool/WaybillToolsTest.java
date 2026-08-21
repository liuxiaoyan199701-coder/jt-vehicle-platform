package io.github.jtconsole.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.view.ViewBudget;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.WaybillRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.support.TestPrincipals;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WaybillToolsTest {
    private final VehicleService vehicles = mock(VehicleService.class);
    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final WaybillTools tools = new WaybillTools(null, vehicles, waybills);

    @Test
    void returnsRecentWaybillPreviewsWithinTheCallerScope() {
        when(vehicles.requireVisibleDevice("device-1", TestPrincipals.platform().scope()))
                .thenReturn("device-1");
        when(waybills.findByDevice("device-1", 1, 2, TestPrincipals.platform().scope()))
                .thenReturn(new WaybillRepository.WaybillPage(List.of(
                        new io.github.jtconsole.domain.Waybill(
                                1, "device-1", "2026-08-20T10:00:00.000+08:00",
                                "2026-08-20T02:00:00Z", 5, "运单预览", true)),
                        1, 1, 2));

        Map<String, Object> result = tools.query(
                "device-1", 2, session(TestPrincipals.platform()));

        assertThat(result).containsEntry("total", 1);
        assertThat(result.get("waybills").toString()).contains("运单预览");
    }

    @Test
    void unauthorizedDeviceReturnsNoRecords() {
        AuthorizedPrincipal principal = TestPrincipals.tenantAdmin(2, 2);
        when(vehicles.requireVisibleDevice("device-1", principal.scope()))
                .thenThrow(IamException.notFound("车辆不存在"));

        Map<String, Object> result = tools.query("device-1", 10, session(principal));

        assertThat(result).containsEntry("total", 0);
        assertThat(result.get("waybills")).isEqualTo(List.of());
        org.mockito.Mockito.verifyNoInteractions(waybills);
    }

    private static ToolSession session(AuthorizedPrincipal principal) {
        return new ToolSession(principal, principal.scope(), ZoneId.of("Asia/Shanghai"),
                AgentEventSink.noop(), ConfirmationPolicy.confirmEverything(), new ViewBudget(),
                new ToolRoundBudget(8));
    }
}
