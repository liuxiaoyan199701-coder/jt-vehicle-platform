package io.github.jtconsole.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.view.ViewBudget;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.DeviceDiagnosticsService;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestPrincipals;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

class DiagnosticsToolsTest {
    private DeviceDiagnosticsService diagnostics;
    private DiagnosticsTools tools;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        diagnostics = mock(DeviceDiagnosticsService.class);
        ConsoleProperties properties = new ConsoleProperties();
        ToolRunner runner = new ToolRunner(JsonMapper.builder().build(), properties);
        tools = new DiagnosticsTools(runner, diagnostics);
        var principal = TestPrincipals.tenantAdmin(7L, 42L);
        ToolSession session = new ToolSession(
                principal, principal.scope(), ZoneId.of("Asia/Shanghai"), AgentEventSink.noop(),
                ConfirmationPolicy.confirmEverything(), new ViewBudget(), new ToolRoundBudget(8));
        context = new ToolContext(session.asContext());
    }

    @Test
    void unauthorizedDeviceReturnsEmptyDiagnosisAndDoesNotProbeDimensions() {
        doThrow(IamException.notFound("车辆不存在"))
                .when(diagnostics).authorize(anyString(), anyString(), anyString(), any(DataScope.class));

        String result = tools.diagnoseDevice(
                "foreign", "2026-08-20T00:00:00+08:00", "2026-08-21T00:00:00+08:00", context);

        assertThat(result).contains("\"available\":false")
                .contains("\"timeline\":[]")
                .doesNotContain("车辆不存在");
        verify(diagnostics, never()).diagnose(anyString(), anyString(), anyString(), any(DataScope.class));
    }

    @Test
    void timeSpanOverSevenDaysIsRejectedBeforeAuthorization() {
        String result = tools.diagnoseDevice(
                "device-1", "2026-08-01T00:00:00+08:00", "2026-08-09T00:00:00+08:00", context);

        assertThat(result).contains("体检时间跨度不能超过 7 天");
        verify(diagnostics, never()).authorize(anyString(), anyString(), anyString(), any(DataScope.class));
    }

    @Test
    void noConnectionRecordsKeepAnHonestlyEmptySummary() {
        Map<String, Object> connection = Map.of(
                "summary", Map.of("eventCount", 0, "registrationFailures", Map.of()),
                "timeline", List.of(), "total", 0);
        Map<String, Object> diagnosis = Map.of(
                "deviceId", "device-1", "connection", connection,
                "clock", Map.of("available", false),
                "positioning", Map.of("available", true),
                "photoFollowUp", Map.of("available", true),
                "streamFollowUp", Map.of("available", true));
        when(diagnostics.diagnose(anyString(), anyString(), anyString(), any(DataScope.class)))
                .thenReturn(diagnosis);

        String result = tools.diagnoseDevice(
                "device-1", "2026-08-20T00:00:00+08:00", "2026-08-21T00:00:00+08:00", context);

        assertThat(result).contains("\"eventCount\":0")
                .contains("\"timeline\":[]")
                .contains("\"registrationFailures\":{}");
    }
}
