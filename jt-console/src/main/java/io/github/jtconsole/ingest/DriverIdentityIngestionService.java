package io.github.jtconsole.ingest;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Driver;
import io.github.jtconsole.domain.DriverIdentityEvent;
import io.github.jtconsole.domain.DriverSession;
import io.github.jtconsole.repository.DriverRepository;
import io.github.jtconsole.security.DataScope;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把网关投递的 0702 驾驶员身份识别事件持久化，并维护车辆驾驶区间。
 *
 * <p>时间口径与 {@link LocationSample} 一致：deviceTime 走 {@link Timestamps#ofDeviceLocal}、
 * receivedAt 走 {@link Timestamps#normalize}——两步都不能省，漏一步就会在按时间筛选时静默少查
 * （见 V8 迁移注释）。
 *
 * <p>插卡（status=0）先结束该车已有未结束区间，再开新区间；拔卡（status=1）结束当前区间；
 * IC 卡读取失败（cardStatus≠0）只留痕、不动区间。重复投递由事件表 event_id 唯一键兜底。
 */
@Service
public class DriverIdentityIngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DriverIdentityIngestionService.class);

    /** JT/T 808 驾驶员身份信息采集上报 0x0702 */
    private static final long DRIVER_IDENTITY = 0x0702L;

    private final DriverRepository drivers;

    public DriverIdentityIngestionService(DriverRepository drivers) {
        this.drivers = drivers;
    }

    /**
     * @return true 表示这条消息是 0702 且已处理；false 表示与本服务无关
     */
    public boolean handleIfDriverIdentity(MessageEnvelope envelope) {
        if (envelope.messageId() == null || envelope.messageId() != DRIVER_IDENTITY) {
            return false;
        }
        Map<String, Object> payload = envelope.payload();
        if (payload == null) {
            return true;
        }

        int status = asInt(payload.get("status"), 0);
        int cardStatus = asInt(payload.get("cardStatus"), 0);
        String name = asString(payload.get("name"), null);
        String licenseNo = asString(payload.get("licenseNo"), null);
        String institution = asString(payload.get("institution"), null);
        String licenseValidPeriod = asString(payload.get("licenseValidPeriod"), null);
        String idCard = asString(payload.get("idCard"), null);

        String deviceTime = Timestamps.ofDeviceLocal(asString(payload.get("dateTime"), null));
        String receivedAt = Timestamps.normalize(
                envelope.receivedAt() == null ? Timestamps.now() : envelope.receivedAt());

        // 从业资格证编码是现实世界的唯一证件编号，全局匹配即语义正确。
        Long driverId = licenseNo == null || licenseNo.isBlank()
                ? null
                : drivers.findByLicenseNo(licenseNo, DataScope.platform())
                        .map(Driver::id).orElse(null);

        boolean inserted = drivers.insertIdentityEvent(new DriverIdentityEvent(
                null, envelope.eventId(), envelope.deviceId(), status, cardStatus,
                name, licenseNo, institution, licenseValidPeriod, idCard, driverId,
                deviceTime, receivedAt));
        if (!inserted) {
            LOGGER.debug("Duplicate 0702 event {} from {}", envelope.eventId(), envelope.deviceId());
            return true;
        }

        if (cardStatus != 0) {
            // 读取失败只留痕，不产生驾驶区间。
            return true;
        }
        if (status == 0) {
            drivers.closeOpenSession(envelope.deviceId(), deviceTime);
            drivers.openSession(envelope.deviceId(), driverId, name, licenseNo,
                    deviceTime, DriverSession.SOURCE_CARD);
        } else if (status == 1) {
            drivers.closeOpenSession(envelope.deviceId(), deviceTime);
        }
        LOGGER.info("Stored 0702 identity event for {}: status={} cardStatus={} driverId={}",
                envelope.deviceId(), status, cardStatus, driverId);
        return true;
    }

    private static Integer asInt(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }
}
