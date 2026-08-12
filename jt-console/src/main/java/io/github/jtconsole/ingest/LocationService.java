package io.github.jtconsole.ingest;

import io.github.jtconsole.geo.CoordTransform;
import io.github.jtconsole.domain.AlarmDefinition;
import io.github.jtconsole.operations.AlarmService;
import io.github.jtconsole.operations.DailyStatService;
import io.github.jtconsole.operations.GeofenceService;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.repository.TrackRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 解析网关投递的位置汇报并落库。
 *
 * <p>网关已经在 {@code ProtocolPayloadMapper.enrichLocation} 里把 1/10^6 度换算成十进制度、
 * 把 1/10 km/h 换算成 km/h，所以这里拿到的 {@code latitude}/{@code longitude}/{@code speedKph}
 * 可直接使用，不需要再做单位换算。需要我们自己做的是 WGS-84 到 GCJ-02 的坐标系转换。
 */
@Service
public class LocationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationService.class);

    /** 位置附加信息 0x01：里程，单位 1/10 km */
    private static final String ATTR_MILEAGE = "0x1";

    private final TrackRepository tracks;
    private final StatusRepository statuses;
    private final ObjectMapper objectMapper;
    private final AlarmService alarms;
    private final GeofenceService geofences;
    private final DailyStatService dailyStats;

    public LocationService(
            TrackRepository tracks,
            StatusRepository statuses,
            ObjectMapper objectMapper,
            AlarmService alarms,
            GeofenceService geofences,
            DailyStatService dailyStats) {
        this.tracks = tracks;
        this.statuses = statuses;
        this.objectMapper = objectMapper;
        this.alarms = alarms;
        this.geofences = geofences;
        this.dailyStats = dailyStats;
    }

    /**
     * @return 该消息的处理结果，仅用于诊断展示
     */
    public LocationHandlingResult handle(MessageEnvelope envelope) {
        String deviceId = envelope.deviceId();
        String receivedAt = normalizeReceivedAt(envelope.receivedAt());

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

        String deviceTime = asString(payload.get("deviceTime"));
        Map<String, Object> alarmFlags = asMap(payload.get("alarmFlags"));
        boolean alarmFlagsPresent = payload.get("alarmFlags") instanceof Map<?, ?>;
        List<String> activeAlarms = activeProtocolAlarms(alarmFlags);

        Double lat = asDouble(payload.get("latitude"));
        Double lng = asDouble(payload.get("longitude"));
        Map<String, Object> statusFlags = asMap(payload.get("statusFlags"));
        Boolean positioned = asBoolean(statusFlags.get("positioned"));

        Double alarmGcjLat = null;
        Double alarmGcjLng = null;
        if (lat != null && lng != null && !isInvalid(lat, lng) && !Boolean.FALSE.equals(positioned)) {
            double[] alarmGcj = CoordTransform.wgs84ToGcj02(lat, lng);
            alarmGcjLat = alarmGcj[0];
            alarmGcjLng = alarmGcj[1];
        }
        // 终端未定位时经纬度通常是 0 或上一次的残值，写进轨迹会在地图上拉出一条到非洲外海的直线
        if (lat == null || lng == null || isInvalid(lat, lng) || Boolean.FALSE.equals(positioned)) {
            if (statuses.touch(deviceId, receivedAt)) {
                alarms.syncProtocol(deviceId, alarmFlags, alarmFlagsPresent, receivedAt,
                        deviceTime, receivedAt, alarmGcjLat, alarmGcjLng);
            }
            LOGGER.debug("Skipping unpositioned location report from {}", deviceId);
            return LocationHandlingResult.withoutLiveUpdate("unpositioned");
        }

        double[] gcj = CoordTransform.wgs84ToGcj02(lat, lng);
        Map<String, Object> attributes = asMap(payload.get("attributes"));

        Double speedKph = asDouble(payload.get("speedKph"));
        Integer direction = asInteger(payload.get("direction"));
        Integer altitude = asInteger(payload.get("altitude"));
        Double mileage = mileageKm(attributes);
        Boolean accOn = asBoolean(statusFlags.get("accOn"));

        String alarmJson = writeJson(activeAlarms);
        String statusJson = writeJson(activeFlags(statusFlags));

        tracks.insert(deviceId, deviceTime, receivedAt, lat, lng, gcj[0], gcj[1],
                speedKph, direction, altitude, mileage, alarmJson);

        boolean latest = statuses.upsertLocation(deviceId, deviceTime, receivedAt, lat, lng, gcj[0], gcj[1],
                speedKph, direction, altitude, mileage, accOn, positioned, alarmJson, statusJson);

        dailyStats.record(deviceId, deviceTime, receivedAt, lat, lng, speedKph, mileage);
        if (!latest) {
            return LocationHandlingResult.withoutLiveUpdate("stale-location");
        }
        alarms.syncProtocol(deviceId, alarmFlags, alarmFlagsPresent, receivedAt,
                deviceTime, receivedAt, alarmGcjLat, alarmGcjLng);
        geofences.evaluate(deviceId, deviceTime, receivedAt, gcj[0], gcj[1], speedKph);
        int activeAlarmCount = alarms.activeCount(deviceId);

        return new LocationHandlingResult("located",
                liveUpdate(deviceId, deviceTime, receivedAt, lat, lng, gcj,
                        speedKph, direction, altitude, mileage, accOn,
                        activeAlarms, activeAlarmCount));
    }

    private Map<String, Object> liveUpdate(
            String deviceId,
            String deviceTime,
            String receivedAt,
            double lat,
            double lng,
            double[] gcj,
            Double speedKph,
            Integer direction,
            Integer altitude,
            Double mileage,
            Boolean accOn,
            List<String> alarms,
            int activeAlarmCount) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("deviceId", deviceId);
        update.put("deviceTime", deviceTime);
        update.put("receivedAt", receivedAt);
        update.put("lat", lat);
        update.put("lng", lng);
        update.put("gcjLat", gcj[0]);
        update.put("gcjLng", gcj[1]);
        update.put("speedKph", speedKph);
        update.put("direction", direction);
        update.put("altitude", altitude);
        update.put("mileage", mileage);
        update.put("accOn", accOn);
        update.put("online", true);
        update.put("alarms", List.copyOf(alarms));
        update.put("activeAlarmCount", activeAlarmCount);
        return update;
    }

    /**
     * 经纬度 0,0 落在几内亚湾，是终端未定位时的典型残值，一律视为无效。
     */
    private static boolean isInvalid(double lat, double lng) {
        return (Math.abs(lat) < 0.000001D && Math.abs(lng) < 0.000001D)
                || Math.abs(lat) > 90.0D
                || Math.abs(lng) > 180.0D;
    }

    /**
     * 附加项 0x01 的里程单位是 1/10 km，换算成 km。
     */
    private static Double mileageKm(Map<String, Object> attributes) {
        Double raw = asDouble(attributes.get(ATTR_MILEAGE));
        return raw == null ? null : raw / 10.0D;
    }

    /**
     * 只保留取值为 true 的位，避免把 32 个恒为 false 的字段写进每一行。
     */
    private static List<String> activeFlags(Map<String, Object> flags) {
        List<String> active = new ArrayList<>();
        flags.forEach((name, value) -> {
            if (Boolean.TRUE.equals(asBoolean(value))) {
                active.add(name);
            }
        });
        return active;
    }

    private static List<String> activeProtocolAlarms(Map<String, Object> flags) {
        List<String> active = new ArrayList<>();
        flags.forEach((name, value) -> {
            if (Boolean.TRUE.equals(asBoolean(value)) && AlarmDefinition.protocol(name).isPresent()) {
                active.add(name);
            }
        });
        return active;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException failure) {
            LOGGER.warn("Failed to serialize flags", failure);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String normalizeReceivedAt(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return Instant.parse(value.trim()).toString();
            } catch (DateTimeParseException ignored) {
                // 非法接收时间不拒绝投递，使用平台当前时间维持稳定排序。
            }
        }
        return Instant.now().toString();
    }
}
