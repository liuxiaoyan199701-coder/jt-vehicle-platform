package io.github.jtconsole.repository;

import io.github.jtconsole.domain.VehicleDailyStat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DailyStatRepository {

    private final JdbcClient jdbc;

    public DailyStatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VehicleDailyStat> find(String deviceId, String date) {
        return jdbc.sql("""
                        SELECT device_id, stat_date, distance_km, point_count, moving_points,
                               max_speed_kph, alarm_count, last_lat, last_lng, last_mileage,
                               last_device_time
                        FROM vehicle_daily_stat WHERE device_id = ? AND stat_date = ?
                        """)
                .param(deviceId).param(date).query(DailyStatRepository::map).optional();
    }

    public void save(VehicleDailyStat value, String updatedAt) {
        jdbc.sql("""
                        INSERT INTO vehicle_daily_stat (
                            device_id, stat_date, distance_km, point_count, moving_points,
                            max_speed_kph, alarm_count, last_lat, last_lng, last_mileage,
                            last_device_time, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(device_id, stat_date) DO UPDATE SET
                            distance_km = excluded.distance_km,
                            point_count = excluded.point_count,
                            moving_points = excluded.moving_points,
                            max_speed_kph = excluded.max_speed_kph,
                            alarm_count = excluded.alarm_count,
                            last_lat = excluded.last_lat, last_lng = excluded.last_lng,
                            last_mileage = excluded.last_mileage,
                            last_device_time = excluded.last_device_time,
                            updated_at = excluded.updated_at
                        """)
                .param(value.deviceId()).param(value.date()).param(value.distanceKm())
                .param(value.pointCount()).param(value.movingPoints()).param(value.maxSpeedKph())
                .param(value.alarmCount()).param(value.lastLat()).param(value.lastLng())
                .param(value.lastMileage()).param(value.lastDeviceTime()).param(updatedAt).update();
    }

    public void incrementAlarm(String deviceId, String date, int count, String updatedAt) {
        jdbc.sql("""
                        INSERT INTO vehicle_daily_stat (
                            device_id, stat_date, distance_km, point_count, moving_points,
                            max_speed_kph, alarm_count, updated_at)
                        VALUES (?, ?, 0, 0, 0, 0, ?, ?)
                        ON CONFLICT(device_id, stat_date) DO UPDATE SET
                            alarm_count = alarm_count + excluded.alarm_count,
                            updated_at = excluded.updated_at
                        """)
                .param(deviceId).param(date).param(count).param(updatedAt).update();
    }

    public List<VehicleDailyStat> findByDeviceRange(String deviceId, String start, String end) {
        return jdbc.sql("""
                        SELECT device_id, stat_date, distance_km, point_count, moving_points,
                               max_speed_kph, alarm_count, last_lat, last_lng, last_mileage,
                               last_device_time
                        FROM vehicle_daily_stat
                        WHERE device_id = ? AND stat_date >= ? AND stat_date <= ?
                        ORDER BY stat_date
                        """)
                .param(deviceId).param(start).param(end).query(DailyStatRepository::map).list();
    }

    public List<DailyAggregate> aggregateRange(String start, String end) {
        return jdbc.sql("""
                        SELECT s.stat_date, COALESCE(SUM(s.distance_km), 0) distance_km,
                               SUM(CASE WHEN s.point_count > 0 THEN 1 ELSE 0 END) active_vehicles,
                               COALESCE(SUM(s.alarm_count), 0) new_alarms
                        FROM vehicle_daily_stat s JOIN vehicle v ON v.device_id = s.device_id
                        WHERE s.stat_date >= ? AND s.stat_date <= ?
                        GROUP BY s.stat_date ORDER BY s.stat_date
                        """)
                .param(start).param(end)
                .query((rs, row) -> new DailyAggregate(rs.getString("stat_date"),
                        rs.getDouble("distance_km"), rs.getInt("active_vehicles"),
                        rs.getInt("new_alarms"))).list();
    }

    public double totalDistance(String date) {
        Number value = jdbc.sql("""
                        SELECT COALESCE(SUM(s.distance_km), 0)
                        FROM vehicle_daily_stat s JOIN vehicle v ON v.device_id = s.device_id
                        WHERE s.stat_date = ?
                        """)
                .param(date).query(Number.class).single();
        return value == null ? 0 : value.doubleValue();
    }

    private static VehicleDailyStat map(ResultSet rs, int row) throws SQLException {
        return new VehicleDailyStat(rs.getString("device_id"), rs.getString("stat_date"),
                rs.getDouble("distance_km"), rs.getInt("point_count"), rs.getInt("moving_points"),
                rs.getDouble("max_speed_kph"), rs.getInt("alarm_count"),
                nullableDouble(rs, "last_lat"), nullableDouble(rs, "last_lng"),
                nullableDouble(rs, "last_mileage"), rs.getString("last_device_time"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public record DailyAggregate(String date, double distanceKm, int activeVehicles, int newAlarms) {}
}
