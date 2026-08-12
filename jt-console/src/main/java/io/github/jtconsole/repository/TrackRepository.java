package io.github.jtconsole.repository;

import io.github.jtconsole.domain.TrackPoint;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TrackRepository {

    private final JdbcClient jdbc;

    public TrackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
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
        jdbc.sql("""
                        INSERT INTO track_point (device_id, device_time, received_at, lat, lng,
                                                 gcj_lat, gcj_lng, speed_kph, direction, altitude,
                                                 mileage, alarm_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                .update();
    }

    /** 按精确设备键与设备时间范围查询轨迹，可使用联合索引的完整前缀。 */
    public List<TrackPoint> findRange(String deviceId, String start, String end, int limit) {
        return jdbc.sql("""
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
                        ORDER BY device_time
                        LIMIT ?
                        """)
                .param(deviceId)
                .param(start)
                .param(end)
                .param(limit)
                .query(TrackPoint.class)
                .list();
    }

    public int countByDevice(String deviceId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM track_point WHERE device_id = ?")
                .param(deviceId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }
}
