package io.github.jtconsole.repository;

import io.github.jtconsole.domain.Vehicle;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VehicleRepository {

    private final JdbcClient jdbc;

    public VehicleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Vehicle> findAll() {
        return jdbc.sql("""
                        SELECT device_id, plate_no, plate_color, brand, channel_count, remark,
                               created_at, updated_at
                        FROM vehicle
                        ORDER BY plate_no
                        """)
                .query(Vehicle.class)
                .list();
    }

    public Optional<Vehicle> findById(String deviceId) {
        return jdbc.sql("""
                        SELECT device_id, plate_no, plate_color, brand, channel_count, remark,
                               created_at, updated_at
                        FROM vehicle WHERE device_id = ?
                        """)
                .param(deviceId)
                .query(Vehicle.class)
                .optional();
    }

    public void insert(Vehicle vehicle) {
        String now = Instant.now().toString();
        jdbc.sql("""
                        INSERT INTO vehicle (device_id, plate_no, plate_color, brand, channel_count,
                                             remark, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(vehicle.deviceId())
                .param(vehicle.plateNo())
                .param(vehicle.plateColor())
                .param(vehicle.brand())
                .param(vehicle.channelCount())
                .param(vehicle.remark())
                .param(now)
                .param(now)
                .update();
    }

    public int update(Vehicle vehicle) {
        return jdbc.sql("""
                        UPDATE vehicle
                        SET plate_no = ?, plate_color = ?, brand = ?, channel_count = ?,
                            remark = ?, updated_at = ?
                        WHERE device_id = ?
                        """)
                .param(vehicle.plateNo())
                .param(vehicle.plateColor())
                .param(vehicle.brand())
                .param(vehicle.channelCount())
                .param(vehicle.remark())
                .param(Instant.now().toString())
                .param(vehicle.deviceId())
                .update();
    }

    @Transactional
    public int delete(String deviceId) {
        jdbc.sql("DELETE FROM fleet_vehicle WHERE device_id = ?").param(deviceId).update();
        jdbc.sql("DELETE FROM geofence_presence WHERE device_id = ?").param(deviceId).update();
        jdbc.sql("DELETE FROM geofence_vehicle WHERE device_id = ?").param(deviceId).update();
        jdbc.sql("DELETE FROM alarm_condition_state WHERE device_id = ? AND source = 'GEOFENCE'")
                .param(deviceId).update();
        return jdbc.sql("DELETE FROM vehicle WHERE device_id = ?").param(deviceId).update();
    }

    public boolean exists(String deviceId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM vehicle WHERE device_id = ?")
                .param(deviceId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }
}
