package io.github.jtconsole.ingest;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.operations.AlarmService;
import io.github.jtconsole.operations.GeofenceService;
import io.github.jtconsole.operations.RuleService;
import io.github.jtconsole.repository.DeviceAttributeRepository;
import io.github.jtconsole.repository.StatusRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 解析网关投递的位置汇报并落库。
 *
 * <p>字段解析在 {@link LocationSample}，落库在 {@link LocationProjection}；这里负责的是
 * 落库之后的联动——告警同步、围栏判定与实时推送。
 */
@Service
public class LocationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationService.class);

    private final StatusRepository statuses;
    private final LocationProjection projection;
    private final BatchLocationService batches;
    private final AlarmService alarms;
    private final GeofenceService geofences;
    private final RuleService rules;
    private final DeviceAttributeRepository deviceAttributes;

    public LocationService(
            StatusRepository statuses,
            LocationProjection projection,
            BatchLocationService batches,
            AlarmService alarms,
            GeofenceService geofences,
            RuleService rules,
            DeviceAttributeRepository deviceAttributes) {
        this.statuses = statuses;
        this.projection = projection;
        this.batches = batches;
        this.alarms = alarms;
        this.geofences = geofences;
        this.rules = rules;
        this.deviceAttributes = deviceAttributes;
    }

    /**
     * @return 该消息的处理结果，仅用于诊断展示
     */
    public LocationHandlingResult handle(MessageEnvelope envelope) {
        String deviceId = envelope.deviceId();
        String receivedAt = normalizeReceivedAt(envelope.receivedAt());

        // 每条报文都携带协议版本，持续记录以便双版本下行指令（如 0x8500）按正确版本编码
        deviceAttributes.upsertProtocolVersion(deviceId, envelope.protocolVersion());

        if (envelope.isBatchLocationReport()) {
            return batches.handle(envelope, receivedAt);
        }

        if (!envelope.isLocationReport()) {
            // 心跳、鉴权等非位置消息只刷新在线时间
            statuses.touch(deviceId, receivedAt);
            return LocationHandlingResult.withoutLiveUpdate("touched");
        }

        Map<String, Object> payload = envelope.payload();
        if (payload == null) {
            statuses.touch(deviceId, receivedAt);
            return LocationHandlingResult.withoutLiveUpdate("no-payload");
        }

        LocationSample sample = LocationSample.parse(deviceId, receivedAt, payload);

        // 终端未定位时经纬度通常是 0 或上一次的残值，写进轨迹会在地图上拉出一条到非洲外海的直线
        if (!sample.locatable()) {
            if (statuses.touch(deviceId, receivedAt)) {
                syncAlarms(sample);
            }
            LOGGER.debug("Skipping unpositioned location report from {}", deviceId);
            return LocationHandlingResult.withoutLiveUpdate("unpositioned");
        }

        LocationProjection.Outcome outcome = projection.project(sample);
        if (!outcome.latest()) {
            // 位置没能覆盖已存状态，但设备本身确实活着，在线时间仍要刷新
            statuses.touch(deviceId, receivedAt);
            return LocationHandlingResult.withoutLiveUpdate("stale-location");
        }
        syncAlarms(sample);
        geofences.evaluate(deviceId, sample.deviceTime(), receivedAt,
                sample.gcjLat(), sample.gcjLng(), sample.speedKph());
        rules.evaluate(deviceId, sample.deviceTime(), receivedAt,
                sample.gcjLat(), sample.gcjLng(), sample.speedKph(), sample.accOn());

        return new LocationHandlingResult("located",
                liveUpdate(sample, alarms.activeCount(deviceId)));
    }

    private void syncAlarms(LocationSample sample) {
        alarms.syncProtocol(sample.deviceId(), sample.alarmFlags(), sample.alarmFlagsPresent(),
                sample.receivedAt(), sample.deviceTime(), sample.receivedAt(),
                sample.alarmGcjLat(), sample.alarmGcjLng());
    }

    private static Map<String, Object> liveUpdate(LocationSample sample, int activeAlarmCount) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("deviceId", sample.deviceId());
        update.put("deviceTime", sample.deviceTime());
        update.put("receivedAt", sample.receivedAt());
        update.put("lat", sample.lat());
        update.put("lng", sample.lng());
        update.put("gcjLat", sample.gcjLat());
        update.put("gcjLng", sample.gcjLng());
        update.put("speedKph", sample.speedKph());
        update.put("direction", sample.direction());
        update.put("altitude", sample.altitude());
        update.put("mileage", sample.mileage());
        update.put("accOn", sample.accOn());
        update.put("online", true);
        update.put("alarms", List.copyOf(sample.activeAlarms()));
        update.put("activeAlarmCount", activeAlarmCount);
        return update;
    }

    private static String normalizeReceivedAt(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return Timestamps.of(Instant.parse(value.trim()));
            } catch (DateTimeParseException ignored) {
                // 非法接收时间不拒绝投递，使用平台当前时间维持稳定排序。
            }
        }
        return Timestamps.now();
    }
}
