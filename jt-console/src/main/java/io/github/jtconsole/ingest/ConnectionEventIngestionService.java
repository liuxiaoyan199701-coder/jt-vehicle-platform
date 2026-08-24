package io.github.jtconsole.ingest;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.ConnectionEvent;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.repository.ConnectionEventRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ObjectMapper json = new ObjectMapper();

    public ConnectionEventIngestionService(
            ConnectionEventRepository events, DeviceOwnershipCache ownership) {
        this.events = events;
        this.ownership = ownership;
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
                detail(payload.get("detail")));
        events.insertIgnore(event);
        return true;
    }

    /**
     * 链路事件（指令结局、无流到达）的结构化补充信息原样留存为 JSON 文本。
     *
     * <p>不做字段级校验：网关先于控制台发布时会带上控制台尚不认识的字段，
     * 丢弃它们等于丢掉排查线索；序列化失败也只是这一列为空，事件本身照常落库。
     */
    private String detail(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(map);
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
