package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.AuditRecorder;
import io.github.jtconsole.gateway.DeviceDisconnectClient;
import io.github.jtconsole.ingest.RecentEventLog;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.live.LiveBroadcaster;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 排查用接口：看清设备到底在往平台发什么报文。
 *
 * <p>典型场景是「设备日志显示连上了，但界面上看不到车」——原因可能是终端只发心跳不报位置、
 * 位置未定位被跳过、或者 deviceId 与档案对不上。这些从数据库结果里看不出区别。
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private final RecentEventLog recentEvents;
    private final LiveBroadcaster broadcaster;
    private final AuditRecorder audits;
    private final DeviceOwnershipCache ownership;
    private final DeviceDisconnectClient disconnects;

    public DiagnosticsController(
            RecentEventLog recentEvents,
            LiveBroadcaster broadcaster,
            AuditRecorder audits,
            DeviceOwnershipCache ownership,
            DeviceDisconnectClient disconnects) {
        this.recentEvents = recentEvents;
        this.broadcaster = broadcaster;
        this.audits = audits;
        this.ownership = ownership;
        this.disconnects = disconnects;
    }

    /**
     * 最近收到的投递事件。
     *
     * <p>{@code outcome} 含义：
     * <ul>
     *   <li>{@code located} — 位置有效，已写入轨迹</li>
     *   <li>{@code unpositioned} — 位置报文但未定位或坐标无效，只刷新在线时间</li>
     *   <li>{@code touched} — 非位置报文（心跳、鉴权等），只刷新在线时间</li>
     *   <li>{@code duplicate} — 重复投递，已按 eventId 去重</li>
     * </ul>
     */
    @GetMapping("/events")
    @RequirePermission(Permissions.SYSTEM_AUDIT_VIEW)
    public ApiResponse<Map<String, Object>> events(@RequestParam(defaultValue = "30") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", recentEvents.summary());
        result.put("recent", recentEvents.recent(Math.min(Math.max(limit, 1), 100)));
        LiveBroadcaster.BroadcastMetrics metrics = broadcaster.metrics();
        result.put("liveBroadcast", Map.of(
                "subscribers", metrics.subscribers(),
                "queueDepth", metrics.dispatchQueueDepth(),
                "queueCapacity", metrics.dispatchQueueCapacity(),
                "overflows", metrics.dispatchOverflows(),
                "sendFailures", metrics.sendFailures(),
                "slowSessions", metrics.slowSessions()));
        // 审计是异步有界队列，积压与丢弃只有在这里才看得见。
        AuditRecorder.Stats auditStats = audits.stats();
        result.put("audit", Map.of(
                "queued", auditStats.queued(),
                "written", auditStats.written(),
                "dropped", auditStats.dropped(),
                "writeFailures", auditStats.writeFailures()));
        result.put("tenancy", Map.of(
                "ownershipCacheSize", ownership.size(),
                "gatewayDisconnectFailures", disconnects.failureCount()));
        return ApiResponse.ok(result);
    }
}
