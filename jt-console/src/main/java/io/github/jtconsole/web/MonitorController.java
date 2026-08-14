package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.LiveStatus;
import io.github.jtconsole.live.LiveBroadcaster;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final StatusRepository statuses;
    private final LiveBroadcaster broadcaster;

    public MonitorController(StatusRepository statuses, LiveBroadcaster broadcaster) {
        this.statuses = statuses;
        this.broadcaster = broadcaster;
    }

    /**
     * 全部设备的最新位置与在线状态。前端进入监控页时拉一次全量，之后靠 WebSocket 增量更新。
     */
    @GetMapping("/live")
    @RequirePermission(Permissions.MONITOR_VIEW)
    public ApiResponse<List<LiveStatus>> live(DataScope scope) {
        return ApiResponse.ok(statuses.findAllLive(scope));
    }

    @GetMapping("/stats")
    @RequirePermission(Permissions.MONITOR_VIEW)
    public ApiResponse<Map<String, Object>> stats(DataScope scope) {
        List<LiveStatus> all = statuses.findAllLive(scope);
        long online = all.stream().filter(LiveStatus::online).count();
        LiveBroadcaster.BroadcastMetrics metrics = broadcaster.metrics();
        return ApiResponse.ok(Map.of(
                "total", all.size(),
                "online", online,
                "offline", all.size() - online,
                "subscribers", metrics.subscribers(),
                "broadcastQueueDepth", metrics.dispatchQueueDepth(),
                "broadcastQueueCapacity", metrics.dispatchQueueCapacity(),
                "broadcastOverflows", metrics.dispatchOverflows(),
                "broadcastSendFailures", metrics.sendFailures(),
                "slowSubscribers", metrics.slowSessions()));
    }
}
