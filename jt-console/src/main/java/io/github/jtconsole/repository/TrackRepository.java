package io.github.jtconsole.repository;

import io.github.jtconsole.domain.TrackPoint;
import io.github.jtconsole.security.DataScope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TrackRepository {

    private final JdbcClient jdbc;

    public TrackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一个轨迹点。
     *
     * <p>同一设备的同一设备时间只保留一条：补传的时间窗常与已实时上报的点重叠，重复写入会让
     * 轨迹出现重影、日里程被重复累加。冲突即忽略而不是先查后写——SQLite 并发投递下先查后写仍有
     * 竞态，数据库约束才是唯一可靠的位置。
     *
     * @return 真正插入了新行时为 {@code true}；命中已有点而被忽略时为 {@code false}
     */
    public boolean insert(
            String deviceId,
            String deviceTime,
            String receivedAt,
            double lat,
            double lng,
            double gcjLat,
            double gcjLng,
            Double speedKph,
            Integer direction,
            Integer altitude,
            Double mileage,
            String alarmJson) {
        return jdbc.sql("""
                        INSERT INTO track_point (device_id, device_time, received_at, lat, lng,
                                                 gcj_lat, gcj_lng, speed_kph, direction, altitude,
                                                 mileage, alarm_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (device_id, device_time) DO NOTHING
                        """)
                .param(deviceId)
                .param(deviceTime)
                .param(receivedAt)
                .param(lat)
                .param(lng)
                .param(gcjLat)
                .param(gcjLng)
                .param(speedKph)
                .param(direction)
                .param(altitude)
                .param(mileage)
                .param(alarmJson)
                .update() > 0;
    }

    /**
     * 按精确设备键与设备时间范围查询轨迹，可使用联合索引的完整前缀。
     *
     * <p>{@code track_point} 不带租户列——投递写入时只有 deviceId，且设备跨租户调拨后
     * 历史行上的租户值会变成脏数据。范围过滤统一经车辆档案这张唯一权威映射。
     *
     * <p>时间边界经 {@link TimeBounds} 规范化：{@code device_time} 是字符串，比较走字节序，
     * 边界写成空格分隔会让同一天的点一个都查不出来。原因见 {@code TimeBounds} 的类注释。
     */
    public List<TrackPoint> findRange(
            String deviceId, String start, String end, int limit, DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        // 边界为空表示该侧不设限。直接把 null 交给比较运算会让整个条件变成 NULL，静默返回空集。
        String lower = TimeBounds.lower(start) == null ? "0000-01-01T00:00:00.000+08:00" : TimeBounds.lower(start);
        String upper = TimeBounds.upper(end) == null ? "9999-12-31T23:59:59.999+08:00" : TimeBounds.upper(end);
        String sql = """
                SELECT device_time AS deviceTime,
                       received_at AS receivedAt,
                       lat, lng,
                       gcj_lat  AS gcjLat,
                       gcj_lng  AS gcjLng,
                       speed_kph AS speedKph,
                       direction, altitude, mileage
                FROM track_point
                WHERE device_id = ?
                  AND device_time >= ?
                  AND device_time <= ?
                """ + scope.deviceCondition("device_id") + """
                ORDER BY device_time
                LIMIT ?
                """;
        List<Object> params = new ArrayList<>();
        params.add(deviceId);
        params.add(lower);
        params.add(upper);
        params.addAll(scope.parameters());
        params.add(limit);
        return jdbc.sql(sql).params(params).query(TrackPoint.class).list();
    }


    public int countByDevice(String deviceId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM track_point WHERE device_id = ?")
                .param(deviceId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }
}
