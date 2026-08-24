package io.github.jtconsole.ingest;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.ConnectionEvent;
import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.repository.DeviceLogRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 把 type=connection 的网关信封归因并落库；归因只发生在 ingest 时刻，不追溯回填。 */
@Service
public class ConnectionEventIngestionService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ConnectionEventIngestionService.class);

    private final ConnectionEventRepository events;
    private final DeviceOwnershipCache ownership;
    private final DeviceLogRepository deviceLogs;
    private final ObjectMapper json = new ObjectMapper();

    /** 测试便利构造：不双写日志库。 */
    public ConnectionEventIngestionService(
            ConnectionEventRepository events, DeviceOwnershipCache ownership) {
        this(events, ownership, null);
    }

    @Autowired
    public ConnectionEventIngestionService(
            ConnectionEventRepository events, DeviceOwnershipCache ownership,
            DeviceLogRepository deviceLogs) {
        this.events = events;
        this.ownership = ownership;
        this.deviceLogs = deviceLogs;
    }

    public boolean handle(MessageEnvelope envelope) {
        if (envelope == null || !"connection".equalsIgnoreCase(envelope.type())) {
            return false;
        }
        Map<String, Object> payload = envelope.payload() == null ? Map.of() : envelope.payload();
        String deviceId = envelope.deviceId();
        Long tenantId = ownership.find(deviceId).map(DeviceOwnershipCache.Ownership::tenantId).orElse(null);
        ConnectionEvent event = new ConnectionEvent(
                0,
                envelope.eventId(),
                deviceId,
                tenantId,
                text(payload, "kind", "UNKNOWN"),
                integer(payload.get("reasonCode")),
                text(payload, "reason", null),
                text(payload, "remoteAddr", null),
                Math.max(1, integerOr(payload.get("repeatCount"), 1)),
                normalizeEventTime(payload.get("eventTime"), envelope.receivedAt()),
                normalizeReceivedAt(envelope.receivedAt()),
                detail(payload.get("detail"), text(payload, "terminalId", null)));
        events.insertIgnore(event);
        mirrorToDeviceLog(event, envelope.instanceId());
        return true;
    }

    /**
     * 往日志库补一条 {@code CONNECTION} 记录，让设备时间线单表可查。
     *
     * <p>connection_event 仍是连接事件的**权威源**，诊断只读它；这里写失败只记一条 warn，
     * 日志时间线缺一格远不如把连接投影拖垮严重。{@code event_id} 加后缀，
     * 与未来可能从网关直发的日志信封不会撞唯一键。
     */
    private void mirrorToDeviceLog(ConnectionEvent event, String instanceId) {
        if (deviceLogs == null) {
            return;
        }
        try {
            deviceLogs.insertIgnore(new DeviceLog(
                    0, event.eventId() + ":device-log", event.deviceId(), event.tenantId(),
                    "CONNECTION", null, null, event.eventTime(),
                    summary(event), null, event.detail(), false, false, instanceId));
        } catch (RuntimeException failure) {
            LOGGER.warn("连接事件双写日志库失败：device={}, kind={}",
                    event.deviceId(), event.kind(), failure);
        }
    }

    private static String summary(ConnectionEvent event) {
        return event.reason() == null || event.reason().isBlank()
                ? event.kind()
                : event.kind() + '：' + event.reason();
    }

    /**
     * 链路事件（指令结局、无流到达）的结构化补充信息原样留存为 JSON 文本。
     *
     * <p>不做字段级校验：网关先于控制台发布时会带上控制台尚不认识的字段，
     * 丢弃它们等于丢掉排查线索；序列化失败也只是这一列为空，事件本身照常落库。
     */
    private String detail(Object value, String terminalId) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> merged.put(String.valueOf(key), nested));
        }
        if (terminalId != null) {
            // 事件按手机号归户，终端自报的编号只作附注——放 detail 里不必给业务库加列。
            merged.put("terminalId", terminalId);
        }
        if (merged.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(merged);
        } catch (JacksonException failure) {
            LOGGER.warn("连接事件的 detail 无法序列化，按空处理", failure);
            return null;
        }
    }

    private static String text(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        return value.toString().trim();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int integerOr(Object value, int fallback) {
        Integer parsed = integer(value);
        return parsed == null ? fallback : parsed;
    }

    private static String normalizeEventTime(Object value, String receivedAt) {
        if (value != null && !value.toString().isBlank()) {
            String raw = value.toString().trim();
            try {
                return Timestamps.of(Instant.parse(raw));
            } catch (DateTimeParseException ignored) {
                return Timestamps.normalize(raw);
            }
        }
        return normalizeReceivedAt(receivedAt);
    }

    private static String normalizeReceivedAt(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return Timestamps.of(Instant.parse(value.trim()));
            } catch (DateTimeParseException ignored) {
                return Timestamps.normalize(value);
            }
        }
        return Timestamps.now();
    }
}
