package io.github.jtconsole.operations;

import io.github.jtconsole.domain.ConnectionEvent;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.security.DataScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 连接诊断查询；先做设备存在性/范围判定，再读取事件，避免跨租户探测。 */
@Service
public class ConnectionDiagnosticsService {
    private final ConnectionEventRepository events;
    private final VehicleService vehicles;

    public ConnectionDiagnosticsService(ConnectionEventRepository events, VehicleService vehicles) {
        this.events = events;
        this.vehicles = vehicles;
    }

    public Map<String, Object> query(
            String deviceId, String start, String end, int page, int pageSize, DataScope scope) {
        String device = deviceId == null ? "" : deviceId.trim();
        if (device.isEmpty()) {
            throw new IllegalArgumentException("设备号不能为空");
        }
        // 平台管理员可查看 tenant_id NULL 的未建档事件；其它调用者必须命中自己的车辆档案。
        if (!scope.isPlatform()) {
            vehicles.requireVisibleDevice(device, scope);
        }
        int boundedPage = Math.max(1, page);
        int boundedSize = Math.clamp(pageSize, 1, 100);
        List<ConnectionEvent> all = events.findByDevice(device, start, end, 500, scope);
        int from = Math.min((boundedPage - 1) * boundedSize, all.size());
        int to = Math.min(from + boundedSize, all.size());
        List<ConnectionEvent> timeline = all.subList(from, to);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eventCount", all.size());
        summary.put("lastConnectedAt", all.stream()
                .filter(event -> "CONNECTED".equals(event.kind()))
                .map(ConnectionEvent::eventTime).findFirst().orElse(null));
        summary.put("disconnectReasons", counts(all, "DISCONNECTED"));
        summary.put("registrationFailures", counts(all, "REGISTER_RESULT", true));
        summary.put("authenticationFailures", counts(all, "AUTH_RESULT", true));
        if (all.isEmpty()) {
            summary.put("note", "平台侧查无连接记录：终端未到达平台，方向应排查 SIM 卡、蜂窝网络和终端 IP/端口配置。");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", device);
        result.put("summary", summary);
        result.put("timeline", timeline);
        result.put("page", boundedPage);
        result.put("pageSize", boundedSize);
        result.put("total", all.size());
        return result;
    }

    private static Map<String, Integer> counts(List<ConnectionEvent> events, String kind) {
        return counts(events, kind, false);
    }

    private static Map<String, Integer> counts(
            List<ConnectionEvent> events, String kind, boolean failuresOnly) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ConnectionEvent event : events) {
            if (!kind.equals(event.kind()) || failuresOnly && success(event)) {
                continue;
            }
            String reason = event.reason() == null ? "未知原因" : event.reason();
            result.merge(reason, event.repeatCount(), Integer::sum);
        }
        return result;
    }

    private static boolean success(ConnectionEvent event) {
        return event.reasonCode() != null && event.reasonCode() == 0;
    }
}
