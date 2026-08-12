package io.github.jtconsole.repository;

import io.github.jtconsole.domain.LiveStatus;
import java.time.Instant;
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
     * 位置汇报到达时 UPSERT 设备最新状态。
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
                        WHERE device_status.last_seen_at IS NULL
                           OR julianday(excluded.last_seen_at) > julianday(device_status.last_seen_at)
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
     * 实时监控列表。左连接车辆档案，未建档的设备也会出现（plateNo 为空）。设备键是协议层
     * 已经解码完成的 canonical 字符串，关联必须精确，不能再次做数值化或前导零归一化。
     */
    public List<LiveStatus> findAllLive() {
        return jdbc.sql("""
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
                        FROM device_status s
                        LEFT JOIN vehicle v
                               ON v.device_id = s.device_id
                        ORDER BY s.online DESC, v.plate_no
                        """)
                .query(LiveStatus.class)
                .list();
    }

    public Optional<LiveStatus> findLiveByDevice(String deviceId) {
        return jdbc.sql("""
                        SELECT s.device_id AS deviceId, v.plate_no AS plateNo,
                               s.online AS online, s.last_seen_at AS lastSeenAt,
                               s.device_time AS deviceTime, s.lat, s.lng,
                               s.gcj_lat AS gcjLat, s.gcj_lng AS gcjLng,
                               s.speed_kph AS speedKph, s.direction, s.altitude, s.mileage,
                               s.acc_on AS accOn, s.positioned AS positioned,
                               s.alarm_json AS alarmJson, s.status_json AS statusJson
                        FROM device_status s LEFT JOIN vehicle v ON v.device_id = s.device_id
                        WHERE s.device_id = ?
                        """)
                .param(deviceId).query(LiveStatus.class).optional();
    }

    /** 首页车队口径只统计已建档车辆，未知在线终端单独返回。 */
    public FleetSnapshot fleetSnapshot() {
        return jdbc.sql("""
                        SELECT COUNT(v.device_id) AS fleet_vehicles,
                               SUM(CASE WHEN s.online = 1 THEN 1 ELSE 0 END) AS online,
                               SUM(CASE WHEN s.online = 1 AND COALESCE(s.speed_kph, 0) > 5 THEN 1 ELSE 0 END) AS moving,
                               SUM(CASE WHEN s.online = 1 AND COALESCE(s.speed_kph, 0) <= 5 THEN 1 ELSE 0 END) AS idle,
                               (SELECT COUNT(*) FROM device_status u
                                LEFT JOIN vehicle known ON known.device_id = u.device_id
                                WHERE u.online = 1 AND known.device_id IS NULL) AS unknown_online
                        FROM vehicle v LEFT JOIN device_status s ON s.device_id = v.device_id
                        """)
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
