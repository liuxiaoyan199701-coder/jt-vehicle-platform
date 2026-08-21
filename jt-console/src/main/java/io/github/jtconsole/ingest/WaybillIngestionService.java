package io.github.jtconsole.ingest;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.repository.WaybillRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 把 0x0701 信封无损落为电子运单原文。 */
@Service
public class WaybillIngestionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WaybillIngestionService.class);
    private static final long ELECTRONIC_WAYBILL = 0x0701L;
    private static final DateTimeFormatter COMPACT_DEVICE_TIME =
            DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final WaybillRepository waybills;
    private final DeviceOwnershipCache ownership;

    public WaybillIngestionService(
            WaybillRepository waybills, DeviceOwnershipCache ownership) {
        this.waybills = waybills;
        this.ownership = ownership;
    }

    /** @return true 表示消息属于 0701（即使载荷损坏并被拒绝），false 表示与本服务无关。 */
    public boolean handleIfWaybill(MessageEnvelope envelope) {
        if (envelope.messageId() == null || envelope.messageId() != ELECTRONIC_WAYBILL) {
            return false;
        }
        Map<String, Object> payload = envelope.payload();
        String rawBase64 = payload == null ? null : text(payload.get("rawBase64"));
        if (rawBase64 == null) {
            throw new InvalidEnvelopeException("0701 payload.rawBase64 must not be blank");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(rawBase64);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidEnvelopeException("0701 payload.rawBase64 is invalid");
        }

        String receivedAt = Timestamps.normalize(
                envelope.receivedAt() == null ? Timestamps.now() : envelope.receivedAt());
        String deviceTime = payload == null ? null : firstText(
                payload.get("reportedAt"), payload.get("deviceTime"));
        String reportedAt = deviceTime == null ? receivedAt : normalizeDeviceTime(deviceTime, receivedAt);
        Long tenantId = ownership.find(envelope.deviceId())
                .map(owner -> owner.tenantId())
                .orElse(null);

        boolean inserted = waybills.insertIgnore(
                envelope.eventId(), tenantId, envelope.deviceId(), reportedAt, receivedAt,
                rawBase64, raw.length, Timestamps.now());
        if (inserted) {
            LOGGER.info("Stored 0701 waybill for {}: bytes={} tenantId={}",
                    envelope.deviceId(), raw.length, tenantId);
        }
        return true;
    }

    private static String normalizeDeviceTime(String value, String fallback) {
        try {
            if (value.matches("\\d{12}")) {
                return Timestamps.ofDeviceLocal(
                        LocalDateTime.parse(value, COMPACT_DEVICE_TIME).toString());
            }
            String normalized = Timestamps.ofDeviceLocal(value);
            return normalized == null || normalized.equals(value) && !value.contains("T")
                    ? fallback
                    : normalized;
        } catch (DateTimeParseException invalid) {
            return fallback;
        }
    }

    private static String firstText(Object first, Object second) {
        String value = text(first);
        return value == null ? text(second) : value;
    }

    private static String text(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
