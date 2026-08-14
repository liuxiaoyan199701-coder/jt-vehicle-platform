package io.github.jtconsole.repository;

import io.github.jtconsole.domain.LiveStatus;
import io.github.jtconsole.security.DataScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StatusRepository {

    private final JdbcClient jdbc;

    public StatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 设备时间领先服务器时间多久就判定为时钟异常，单位：天。
     *
     * <p>不能取更小的值：{@code device_time} 是终端本地时间（北京时间），{@code last_seen_at} 是
     * UTC，正常设备在 {@code julianday} 下就已经「领先」8 小时。这个阈值只用来拦截 RTC 未初始化
     * 或跳变到 2099 这类离谱的时钟，不去管时区差与常见的几分钟偏移。
     */
    private static final double CLOCK_AHEAD_TOLERANCE_DAYS = 1.0D;

    /**
     * 位置汇报到达时 UPSERT 设备最新状态。
     *
     * <p>覆盖判定按 {@code device_time} 而不是 {@code received_at}：一条 0x0704 批量补传里的所有点
     * 共享同一个接收时间，用接收时间比较会让除第一个点外的全部点被误判为过期；单点路径上网络抖动
     * 导致的后发先至，也会让较早的定位点覆盖较晚的。
     *
     * <p>设备时间缺失或明显领先服务器时间的点不参与覆盖竞争——空值会被当成最小值，而领先的脏值一旦
     * 写成水位，此后所有正常点都比它「更早」，设备位置就永久停更了。反过来，若库里已经存着这样一个
     * 脏水位（本次变更之前写入的），下一个正常点会把它冲掉。
     *
     * @return 本次是否成为该设备的最新状态
     */
    public boolean upsertLocation(
            String deviceId,
            String deviceTime,
            String lastSeenAt,
            double lat,
            double lng,
            double gcjLat,
            double gcjLng,
            Double speedKph,
            Integer direction,
            Integer altitude,
            Double mileage,
            Boolean accOn,
            Boolean positioned,
            String alarmJson,
            String statusJson) {
        int updated = jdbc.sql("""
                        INSERT INTO device_status (device_id, online, last_seen_at, device_time,
                                                   lat, lng, gcj_lat, gcj_lng, speed_kph, direction,
                                                   altitude, mileage, acc_on, positioned,
                                                   alarm_json, status_json, updated_at)
                        VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(device_id) DO UPDATE SET
                            online = 1,
                            last_seen_at = excluded.last_seen_at,
                            device_time = excluded.device_time,
                            lat = excluded.lat,
                            lng = excluded.lng,
                            gcj_lat = excluded.gcj_lat,
                            gcj_lng = excluded.gcj_lng,
                            speed_kph = excluded.speed_kph,
                            direction = excluded.direction,
                            altitude = excluded.altitude,
                            mileage = excluded.mileage,
                            acc_on = excluded.acc_on,
                            positioned = excluded.positioned,
                            alarm_json = excluded.alarm_json,
                            status_json = excluded.status_json,
                            updated_at = excluded.updated_at
                        WHERE julianday(excluded.device_time) IS NOT NULL
                          AND julianday(excluded.device_time)
                              <= julianday(excluded.last_seen_at) + ?
                          AND (device_status.device_time IS NULL
                               OR julianday(device_status.device_time) IS NULL
                               OR julianday(excluded.device_time)
                                  > julianday(device_status.device_time)
                               OR julianday(device_status.device_time)
                                  > julianday(excluded.last_seen_at) + ?)
                        """)
                .param(deviceId)
                .param(lastSeenAt)
                .param(deviceTime)
                .param(lat)
                .param(lng)
                .param(gcjLat)
                .param(gcjLng)
                .param(speedKph)
                .param(direction)
                .param(altitude)
                .param(mileage)
                .param(accOn == null ? null : (accOn ? 1 : 0))
                .param(positioned == null ? null : (positioned ? 1 : 0))
                .param(alarmJson)
                .param(statusJson)
                .param(Instant.now().toString())
                .param(CLOCK_AHEAD_TOLERANCE_DAYS)
                .param(CLOCK_AHEAD_TOLERANCE_DAYS)
                .update();
        return updated > 0;
    }

    /**
     * 非位置类消息（心跳、鉴权等）刷新在线时间，不动坐标。
     *
     * <p>会为没见过的设备建行：有些终端（例如纯视频车机）只发注册、鉴权、心跳和音视频流，
     * 从不上报 0x0200。如果这里不建行，这类设备在界面上会完全不可见，也就无从对它开流。
     * 建行后它显示为「在线·未定位」，地图上不打点，但可以正常查看视频。
     *
     * <p>网关投递的设备键统一为协议解码后的 mobileNo/SIM，因此注册和后续消息会更新
     * 同一条状态记录。
     */
    public boolean touch(String deviceId, String lastSeenAt) {
        int updated = jdbc.sql("""
                        INSERT INTO device_status (device_id, online, last_seen_at, updated_at)
                        VALUES (?, 1, ?, ?)
                        ON CONFLICT(device_id) DO UPDATE SET
                            online = 1,
                            last_seen_at = excluded.last_seen_at,
                            updated_at = excluded.updated_at
                        WHERE device_status.last_seen_at IS NULL
                           OR julianday(excluded.last_seen_at) > julianday(device_status.last_seen_at)
                        """)
                .param(deviceId)
                .param(lastSeenAt)
                .param(Instant.now().toString())
                .update();
        return updated > 0;
    }

    /**
     * 实时监控列表。设备键是协议层已经解码完成的 canonical 字符串，关联必须精确，
     * 不能再次做数值化或前导零归一化。
     *
     * <p>未建档设备（{@code plateNo} 为空）只对平台管理员出现：租户用户的世界里
     * 只有本租户已建档车辆，因此租户范围下用 {@code INNER JOIN} 而非左连接。
     */
    public List<LiveStatus> findAllLive(DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        boolean unscoped = scope.tenantId() == null;
        String join = unscoped
                ? "LEFT JOIN vehicle v ON v.device_id = s.device_id"
                : "JOIN vehicle v ON v.device_id = s.device_id";
        String sql = LIVE_COLUMNS + " FROM device_status s " + join
                + " WHERE 1 = 1" + scope.vehicleCondition("v")
                + " ORDER BY s.online DESC, v.plate_no";
        return jdbc.sql(sql).params(scope.parameters()).query(LiveStatus.class).list();
    }

    private static final String LIVE_COLUMNS = """
                        SELECT s.device_id      AS deviceId,
                               v.plate_no       AS plateNo,
                               s.online         AS online,
                               s.last_seen_at   AS lastSeenAt,
                               s.device_time    AS deviceTime,
                               s.lat            AS lat,
                               s.lng            AS lng,
                               s.gcj_lat        AS gcjLat,
                               s.gcj_lng        AS gcjLng,
                               s.speed_kph      AS speedKph,
                               s.direction      AS direction,
                               s.altitude       AS altitude,
                               s.mileage        AS mileage,
                               s.acc_on         AS accOn,
                               s.positioned     AS positioned,
                               s.alarm_json     AS alarmJson,
                               s.status_json    AS statusJson
            """;

    public Optional<LiveStatus> findLiveByDevice(String deviceId, DataScope scope) {
        if (scope.empty()) {
            return Optional.empty();
        }
        boolean unscoped = scope.tenantId() == null;
        String join = unscoped
                ? "LEFT JOIN vehicle v ON v.device_id = s.device_id"
                : "JOIN vehicle v ON v.device_id = s.device_id";
        String sql = LIVE_COLUMNS + " FROM device_status s " + join
                + " WHERE s.device_id = ?" + scope.vehicleCondition("v");
        List<Object> params = new ArrayList<>();
        params.add(deviceId);
        params.addAll(scope.parameters());
        return jdbc.sql(sql).params(params).query(LiveStatus.class).optional();
    }

    /**
     * 首页车队口径只统计范围内已建档车辆；未知在线终端只对平台管理员有意义，
     * 租户范围下恒为 0。
     */
    public FleetSnapshot fleetSnapshot(DataScope scope) {
        if (scope.empty()) {
            return new FleetSnapshot(0, 0, 0, 0, 0, 0);
        }
        String unknownOnline = scope.tenantId() == null
                ? """
                  (SELECT COUNT(*) FROM device_status u
                   LEFT JOIN vehicle known ON known.device_id = u.device_id
                   WHERE u.online = 1 AND known.device_id IS NULL)
                  """
                : "0";
        String sql = """
                SELECT COUNT(v.device_id) AS fleet_vehicles,
                       SUM(CASE WHEN s.online = 1 THEN 1 ELSE 0 END) AS online,
                       SUM(CASE WHEN s.online = 1 AND COALESCE(s.speed_kph, 0) > 5 THEN 1 ELSE 0 END) AS moving,
                       SUM(CASE WHEN s.online = 1 AND COALESCE(s.speed_kph, 0) <= 5 THEN 1 ELSE 0 END) AS idle,
                       %s AS unknown_online
                FROM vehicle v LEFT JOIN device_status s ON s.device_id = v.device_id
                WHERE 1 = 1%s
                """.formatted(unknownOnline, scope.vehicleCondition("v"));
        return jdbc.sql(sql).params(scope.parameters())
                .query((rs, row) -> {
                    int fleet = rs.getInt("fleet_vehicles");
                    int online = rs.getInt("online");
                    return new FleetSnapshot(fleet, online, fleet - online,
                            rs.getInt("moving"), rs.getInt("idle"), rs.getInt("unknown_online"));
                }).single();
    }

    /**
     * 把超过阈值没有任何上报的设备标记为离线。
     *
     * @return 本次被标记为离线的设备数
     */
    public int markOfflineBefore(Instant cutoff) {
        return jdbc.sql("""
                        UPDATE device_status SET online = 0
                        WHERE online = 1 AND julianday(last_seen_at) < julianday(?)
                        """)
                .param(cutoff.toString())
                .update();
    }

    public record FleetSnapshot(
            int fleetVehicles, int online, int offline, int moving, int idle, int unknownOnline) {}
}
