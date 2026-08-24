package io.github.jtconsole.ingest;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.repository.DeviceLogRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 把 {@code type=device_log} 的网关信封落进日志库。
 *
 * <p>归因与连接事件同款：只在 ingest 时刻查一次 {@link DeviceOwnershipCache}，查不到记 NULL，
 * 不追溯回填。建档晚于报文的那段日志因此归属为空——平台管理员仍能查到，租户查不到，
 * 这比事后改写历史归属更可解释。
 */
@Service
public class DeviceLogIngestionService {

    private final DeviceLogRepository logs;
    private final DeviceOwnershipCache ownership;

    public DeviceLogIngestionService(DeviceLogRepository logs, DeviceOwnershipCache ownership) {
        this.logs = logs;
        this.ownership = ownership;
    }

    /** @return 是否接手了这个信封 */
    public boolean handle(MessageEnvelope envelope) {
        if (envelope == null || !isDeviceLog(envelope.type())) {
            return false;
        }
        logs.insertIgnore(toRecord(envelope));
        return true;
    }

    public static boolean isDeviceLog(String type) {
        return "device_log".equalsIgnoreCase(type);
    }

    private DeviceLog toRecord(MessageEnvelope envelope) {
        Map<String, Object> payload = envelope.payload() == null ? Map.of() : envelope.payload();
        String deviceId = envelope.deviceId() == null ? "" : envelope.deviceId().trim();
        Long tenantId = ownership.find(deviceId)
                .map(DeviceOwnershipCache.Ownership::tenantId).orElse(null);
        return new DeviceLog(
                0,
                envelope.eventId(),
                deviceId,
                tenantId,
                direction(payload.get("direction")),
                messageId(envelope, payload),
                integer(payload.get("serialNo")),
                normalizeTime(payload.get("logTime"), envelope.receivedAt()),
                text(payload.get("summary")),
                text(payload.get("rawHex")),
                text(payload.get("parsedJson")),
                flag(payload.get("decodeError")),
                flag(payload.get("truncated")),
                envelope.instanceId());
    }

    /**
     * 解码失败的帧没有消息 ID——信封里那个 0 是「不知道」而不是「消息 0x0000」，记 NULL
     * 才不会在按消息 ID 筛选时凭空多出一堆匹配。
     */
    private static Integer messageId(MessageEnvelope envelope, Map<String, Object> payload) {
        if (flag(payload.get("decodeError"))) {
            return null;
        }
        Long messageId = envelope.messageId();
        return messageId == null ? null : (int) (messageId & 0xffff_ffffL);
    }

    private static String direction(Object value) {
        String text = text(value);
        return text == null ? "UP" : text.toUpperCase(java.util.Locale.ROOT);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** 网关侧发的是 {@code "0"} / {@code "1"} 字符串；数字与布尔也一并认，免得改一侧就断。 */
    private static boolean flag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = text(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private static String normalizeTime(Object logTime, String receivedAt) {
        String candidate = text(logTime);
        if (candidate != null) {
            return normalize(candidate);
        }
        return receivedAt == null || receivedAt.isBlank() ? Timestamps.now() : normalize(receivedAt);
    }

    private static String normalize(String value) {
        try {
            return Timestamps.of(Instant.parse(value.trim()));
        } catch (DateTimeParseException notAnInstant) {
            return Timestamps.normalize(value);
        }
    }
}
