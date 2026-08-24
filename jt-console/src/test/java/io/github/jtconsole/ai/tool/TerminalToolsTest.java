package io.github.jtconsole.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.view.ViewBudget;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.TerminalPage;
import io.github.jtconsole.domain.TerminalSummary;
import io.github.jtconsole.operations.TerminalQueryService;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestPrincipals;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

class TerminalToolsTest {

    private final TerminalQueryService terminals = mock(TerminalQueryService.class);
    private final TerminalTools tools = new TerminalTools(
            new ToolRunner(JsonMapper.builder().build(), new ConsoleProperties()), terminals);

    @Test
    void aQueryIsDelegatedWithTheSessionScopeAndComesBackAsCompactRows() {
        DataScope scope = TestPrincipals.tenantAdmin(7L, 1L).scope();
        when(terminals.search(any(), any(), any(), any(), any(), eq(1), eq(20), eq(scope)))
                .thenReturn(new TerminalPage(List.of(terminal(true)), 1, 1, 20));

        String result = tools.queryTerminals(null, null, null, null, context(scope));

        assertThat(result).contains("\"deviceId\":\"138000000000\"")
                .contains("\"terminalId\":\"1380000\"")
                .contains("\"archived\":true")
                .contains("\"reportedPlate\":\"TEST001\"")
                .contains("\"lastRegisteredAt\"");
        // 省市域 ID、协议版本这些细节不该占模型上下文。
        assertThat(result).doesNotContain("provinceId").doesNotContain("protocolVersion");
    }

    @Test
    void theLimitIsClampedRatherThanPassedThroughUnbounded() {
        DataScope scope = DataScope.platform();
        when(terminals.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new TerminalPage(List.of(), 0, 1, 100));

        tools.queryTerminals(null, null, null, 5000, context(scope));

        verify(terminals).search(any(), any(), any(), any(), any(), eq(1), eq(100), eq(scope));
    }

    /** 租户查不到未建档终端是权限边界，不是故障——说成失败会让用户以为系统坏了。 */
    @Test
    void anEmptyTenantResultIsExplainedAsAVisibilityBoundaryNotAFailure() {
        DataScope scope = TestPrincipals.tenantAdmin(7L, 1L).scope();
        when(terminals.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new TerminalPage(List.of(), 0, 1, 20));

        String result = tools.queryTerminals(null, false, null, null, context(scope));

        assertThat(result).contains("未建档终端只有平台管理员看得到");
        assertThat(result).doesNotContain("失败");
    }

    /** 平台管理员查不到，是真的没有——不能套用租户那句权限说明。 */
    @Test
    void anEmptyPlatformResultSaysTheLedgerIsSimplyEmpty() {
        when(terminals.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new TerminalPage(List.of(), 0, 1, 20));

        String result = tools.queryTerminals(null, false, null, null, context(DataScope.platform()));

        assertThat(result).contains("没有匹配的终端");
        assertThat(result).doesNotContain("平台管理员看得到");
    }

    @Test
    void filtersAreForwardedVerbatim() {
        DataScope scope = DataScope.platform();
        when(terminals.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new TerminalPage(List.of(terminal(false)), 1, 1, 20));

        tools.queryTerminals("1380000", false, true, 50, context(scope));

        verify(terminals).search(eq("1380000"), eq(false), eq(true), eq(null), eq(null),
                eq(1), eq(50), eq(scope));
    }

    private static ToolContext context(DataScope scope) {
        AuthorizedPrincipal principal = TestPrincipals.tenantAdmin(7L, 1L);
        return new ToolContext(new ToolSession(
                principal, scope, ZoneId.of("Asia/Shanghai"), AgentEventSink.noop(),
                ConfirmationPolicy.confirmEverything(), new ViewBudget(), new ToolRoundBudget(8))
                .asContext());
    }

    private static TerminalSummary terminal(boolean archived) {
        return new TerminalSummary("138000000000", "1380000", "JT", "SIMULATOR", 31, 100,
                "TEST001", 1, "JT/T 808-2019/1", "2026-08-24T09:00:00.000+08:00",
                "2026-08-24T10:00:00.000+08:00", "注册",
                archived, archived ? "京A12345" : null, archived ? 1L : null,
                true, "2026-08-24T10:05:00.000+08:00");
    }
}
