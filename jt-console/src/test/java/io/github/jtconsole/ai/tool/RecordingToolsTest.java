package io.github.jtconsole.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.view.ViewBudget;
import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.repository.RecordingUploadRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.support.TestPrincipals;
import io.github.jtconsole.web.RecordingProxyController;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordingToolsTest {
    private final RecordingProxyController recordings = mock(RecordingProxyController.class);
    private final RecordingUploadRepository uploads = mock(RecordingUploadRepository.class);
    private final RecordingTools tools = new RecordingTools(null, recordings, uploads);

    @Test
    void unauthorizedDeviceReturnsNoSourceData() {
        when(recordings.search(anyString(), anyInt(), anyString(), any(), any(), any()))
                .thenThrow(IamException.notFound("车辆不存在"));

        Map<String, Object> result = tools.query(
                "other-tenant-device", "2026-08-19T00:00:00+08:00",
                "2026-08-20T00:00:00+08:00", 1, session(TestPrincipals.platform()));

        assertThat(result).containsEntry("note", "没有可查询的录像结果。");
        assertThat(result.toString()).doesNotContain("车辆不存在").doesNotContain("segment-1");
        assertThat(source(result, "platform").get("count")).isEqualTo(0);
        assertThat(source(result, "device").get("count")).isEqualTo(0);
    }

    @Test
    void rejectsRangesOverSevenDays() {
        Map<String, Object> result = tools.query(
                "device-1", "2026-08-01T00:00:00+08:00",
                "2026-08-09T00:00:00+08:00", 1, session(TestPrincipals.platform()));

        assertThat(result.get("error").toString()).contains("不能超过 7 天");
    }

    @Test
    void offlineDeviceIsReportedWithoutHidingPlatformSegments() {
        var platform = new RecordingProxyController.PlatformSource(true, null, List.of(
                new RecordingProxyController.RecordingRange(
                        Instant.parse("2026-08-19T00:00:00Z"),
                        Instant.parse("2026-08-19T00:01:00Z"), 1, "main", "platform")));
        var device = new RecordingProxyController.DeviceSource(false, "设备离线", List.of());
        when(recordings.search(anyString(), anyInt(), anyString(), any(), any(), any()))
                .thenReturn(ApiResponse.ok(new RecordingProxyController.RecordingSearchResult(platform, device)));

        Map<String, Object> result = tools.query(
                "device-1", "2026-08-19T00:00:00+08:00",
                "2026-08-20T00:00:00+08:00", 1, session(TestPrincipals.platform()));

        assertThat(source(result, "platform"))
                .containsEntry("available", true).containsEntry("count", 1);
        assertThat(source(result, "device"))
                .containsEntry("available", false).containsEntry("reason", "设备离线");
        assertThat(result.get("playback").toString()).contains("不能开流").contains("录像回放页");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> source(Map<String, Object> result, String name) {
        return (Map<String, Object>) result.get(name);
    }

    private static ToolSession session(AuthorizedPrincipal principal) {
        return new ToolSession(principal, principal.scope(), ZoneId.of("Asia/Shanghai"),
                AgentEventSink.noop(), ConfirmationPolicy.confirmEverything(), new ViewBudget(),
                new ToolRoundBudget(8));
    }
}
