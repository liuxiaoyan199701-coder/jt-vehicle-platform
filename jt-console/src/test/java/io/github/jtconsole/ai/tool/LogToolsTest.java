package io.github.jtconsole.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.view.ViewBudget;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.domain.DeviceLogPage;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.DeviceLogQueryService;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestPrincipals;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

class LogToolsTest {

    private DeviceLogQueryService logs;
    private LogTools tools;
    private ToolContext context;
    private DataScope scope;

    @BeforeEach
    void setUp() {
        logs = mock(DeviceLogQueryService.class);
        tools = new LogTools(
                new ToolRunner(JsonMapper.builder().build(), new ConsoleProperties()), logs);
        var principal = TestPrincipals.tenantAdmin(7L, 42L);
        scope = principal.scope();
        ToolSession session = new ToolSession(
                principal, scope, ZoneId.of("Asia/Shanghai"), AgentEventSink.noop(),
                ConfirmationPolicy.confirmEverything(), new ViewBudget(), new ToolRoundBudget(8));
        context = new ToolContext(session.asContext());
    }

    @Test
    void aQueryIsDelegatedWithTheSessionScopeAndComesBackAsCompactSummaryRows() {
        when(logs.query(eq("13800138000"), any(), any(), any(), any(), any(), eq(1), eq(50), eq(scope)))
                .thenReturn(new DeviceLogPage(List.of(log(11, "UP", 0x0200)), 1, 1, 50));

        String result = tools.queryDeviceLogs(
                "13800138000", null, null, null, null, null, null, context);

        assertThat(result).contains("\"id\":11").contains("\"msgId\":\"0x0200\"")
                .contains("\"direction\":\"UP\"").contains("\"total\":1");
        // 摘要行不带 hex 与解析正文：一条 0x0200 的解析 JSON 两千多字符，几十条就撑爆上下文。
        assertThat(result).doesNotContain("rawHex").doesNotContain("parsedJson");
    }

    @Test
    void theLimitIsClampedRatherThanPassedThroughUnbounded() {
        when(logs.query(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new DeviceLogPage(List.of(), 0, 1, 200));

        tools.queryDeviceLogs("13800138000", null, null, null, null, null, 5000, context);

        verify(logs).query(eq("13800138000"), any(), any(), any(), any(), any(), eq(1), eq(200), eq(scope));
    }

    /** 越权与不存在给同一个回答，否则这个工具就成了跨租户设备的探测器。 */
    @Test
    void aDeviceOutsideTheScopeYieldsAnEmptyResultInsteadOfAnError() {
        when(logs.query(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenThrow(IamException.notFound("车辆不存在"));

        String result = tools.queryDeviceLogs("foreign", null, null, null, null, null, null, context);

        assertThat(result).contains("\"logs\":[]").contains("\"total\":0")
                .doesNotContain("车辆不存在");
    }

    /** 参数错误必须指出字段与正确格式，模型才能在同一轮里改对重试，不产生用户可见的失败。 */
    @Test
    void aMalformedMessageIdComesBackAsAFieldLevelHintTheModelCanActOn() {
        when(logs.query(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new IllegalArgumentException("消息 ID 格式不正确：两百，请写成 0x0200 或 512"));

        String result = tools.queryDeviceLogs(
                "13800138000", null, null, null, "两百", null, null, context);

        assertThat(result).contains("error").contains("0x0200").contains("512");
    }

    @Test
    void aMissingDeviceIdIsRejectedBeforeTouchingTheQueryService() {
        String result = tools.queryDeviceLogs(" ", null, null, null, null, null, null, context);

        assertThat(result).contains("deviceId").contains("13800138000");
        verify(logs, never()).query(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void anEmptyResultIsReportedAsAFindingRatherThanAFailure() {
        when(logs.query(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new DeviceLogPage(List.of(), 0, 1, 50));

        String result = tools.queryDeviceLogs(
                "13800138000", null, null, "DOWN", null, null, null, context);

        assertThat(result).contains("不是查询失败");
    }

    @Test
    void theDetailToolReturnsTheRawFrameAndTheParsedBody() {
        when(logs.findById(11L, scope)).thenReturn(Optional.of(log(11, "UP", 0x0200)));

        String result = tools.getDeviceLogDetail(11L, context);

        assertThat(result).contains("7e0200").contains("speedKph").contains("\"id\":11");
    }

    @Test
    void anIdOutsideTheScopeIsIndistinguishableFromAMissingOne() {
        when(logs.findById(anyLong(), any())).thenReturn(Optional.empty());

        String result = tools.getDeviceLogDetail(999L, context);

        assertThat(result).contains("error").contains("query_device_logs");
    }

    @Test
    void aMissingIdIsRejectedWithTheFieldName() {
        String result = tools.getDeviceLogDetail(null, context);

        assertThat(result).contains("id");
        verify(logs, never()).findById(anyLong(), any());
    }

    private static DeviceLog log(long id, String direction, int msgId) {
        return new DeviceLog(id, "evt-" + id, "13800138000", 7L, direction, msgId, 3,
                "2026-08-24T09:02:03.000+08:00", "位置信息汇报", "7e0200",
                "{\"speedKph\":6.0}", false, false, "signal-1");
    }
}
