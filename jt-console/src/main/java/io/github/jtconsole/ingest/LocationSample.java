package io.github.jtconsole.ingest;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.AlarmDefinition;
import io.github.jtconsole.geo.CoordTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一条位置汇报解析完成后的形态，是单点（0x0200）与批量（0x0704 的每个 item）共用的投影输入。
 *
 * <p>网关已经在 {@code ProtocolPayloadMapper.enrichLocation} 里把 1/10^6 度换算成十进制度、
 * 把 1/10 km/h 换算成 km/h，所以这里拿到的经纬度与速度可直接使用，不需要再做单位换算。
 * 需要自己做的是 WGS-84 到 GCJ-02 的坐标系转换。批量报文的每个 item 经同一套增强后与顶层
 * 0x0200 字段形态完全一致，因此可以走同一个解析入口。
 *
 * @param locatable  坐标是否可用于打点。终端未定位时经纬度通常是 0 或上一次的残值，
 *                   写进轨迹会在地图上拉出一条到非洲外海的直线
 * @param alarmGcjLat 告警落点纬度。即使 {@code locatable} 为 false 也可能有值——
 *                    未定位不代表不该开单，只代表不该打点
 */
public record LocationSample(
        String deviceId,
        String deviceTime,
        String receivedAt,
        boolean locatable,
        double lat,
        double lng,
        double gcjLat,
        double gcjLng,
        Double alarmGcjLat,
        Double alarmGcjLng,
        Double speedKph,
        Integer direction,
        Integer altitude,
        Double mileage,
        Boolean accOn,
        Boolean positioned,
        Map<String, Object> alarmFlags,
        boolean alarmFlagsPresent,
        List<String> activeAlarms,
        List<String> activeStatusFlags) {

    /** 位置附加信息 0x01：里程，单位 1/10 km */
    private static final String ATTR_MILEAGE = "0x1";

    /**
     * 解析一条位置 payload。无论坐标是否可用都会返回结果，由调用方按 {@link #locatable()} 决定去向。
     */
    public static LocationSample parse(
            String deviceId, String receivedAt, Map<String, Object> payload) {
        Map<String, Object> alarmFlags = asMap(payload.get("alarmFlags"));
        Map<String, Object> statusFlags = asMap(payload.get("statusFlags"));
        Map<String, Object> attributes = asMap(payload.get("attributes"));
        Boolean positioned = asBoolean(statusFlags.get("positioned"));

        Double lat = asDouble(payload.get("latitude"));
        Double lng = asDouble(payload.get("longitude"));
        boolean usable = lat != null && lng != null && !isInvalid(lat, lng)
                && !Boolean.FALSE.equals(positioned);

        double[] gcj = usable ? CoordTransform.wgs84ToGcj02(lat, lng) : new double[] {0, 0};

        return new LocationSample(
                deviceId,
                // 终端上报的时间不带时区（协议层面就是 BCD 的 YYMMDDHHMMSS），按标准即终端本地
                // 时间。这里补上 +08:00，把一直以来的隐含约定写明确，也让它能与库里其它时间列直接比较。
                Timestamps.ofDeviceLocal(asString(payload.get("deviceTime"))),
                Timestamps.normalize(receivedAt),
                usable,
                usable ? lat : 0,
                usable ? lng : 0,
                gcj[0],
                gcj[1],
                usable ? gcj[0] : null,
                usable ? gcj[1] : null,
                asDouble(payload.get("speedKph")),
                asInteger(payload.get("direction")),
                asInteger(payload.get("altitude")),
                mileageKm(attributes),
                asBoolean(statusFlags.get("accOn")),
                positioned,
                alarmFlags,
                payload.get("alarmFlags") instanceof Map<?, ?>,
                activeProtocolAlarms(alarmFlags),
                activeFlags(statusFlags));
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
}
