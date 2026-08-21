package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Geofence;
import io.github.jtconsole.domain.GeofenceCandidate;
import io.github.jtconsole.domain.GeofenceShape;
import io.github.jtconsole.security.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class GeofenceRepository {

    private static final String SELECT = """
            SELECT g.id, g.name, g.center_gcj_lat, g.center_gcj_lng, g.radius_meters,
                   g.shape, g.points,
                   g.color, g.enabled, g.alert_on_enter, g.alert_on_exit, g.speed_limit_kph,
                   g.tenant_id, g.created_at, g.updated_at,
                   (SELECT COUNT(*) FROM geofence_vehicle gv WHERE gv.geofence_id = g.id) assigned_count
            FROM geofence g
            """;
    private final JdbcClient jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public GeofenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Geofence> findAll(DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE 1 = 1" + scope.tenantCondition("g")
                        + " ORDER BY g.name, g.id")
                .params(scope.tenantParameters()).query(this::map).list();
    }

    public Optional<Geofence> findById(long id, DataScope scope) {
        if (scope.empty()) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " WHERE g.id = ?" + scope.tenantCondition("g"))
                .param(id).params(scope.tenantParameters()).query(this::map).optional();
    }

    public Optional<Long> findTenantId(long geofenceId) {
        return jdbc.sql("SELECT tenant_id FROM geofence WHERE id = ?")
                .param(geofenceId).query(Long.class).optional();
    }

    public long insert(Geofence value) {
        String now = Timestamps.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO geofence (
                            name, center_gcj_lat, center_gcj_lng, radius_meters, shape, points, color,
                            enabled, alert_on_enter, alert_on_exit, speed_limit_kph,
                            tenant_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(value.name()).param(value.centerGcjLat()).param(value.centerGcjLng())
                .param(value.radiusMeters()).param(value.shape().wireValue())
                .param(serializePoints(value.points())).param(value.color()).param(value.enabled() ? 1 : 0)
                .param(value.alertOnEnter() ? 1 : 0).param(value.alertOnExit() ? 1 : 0)
                .param(value.speedLimitKph()).param(value.tenantId())
                .param(now).param(now).update(key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("创建围栏后未返回主键");
        return id.longValue();
    }

    public int update(long id, Geofence value) {
        return jdbc.sql("""
                        UPDATE geofence SET name = ?, center_gcj_lat = ?, center_gcj_lng = ?,
                            radius_meters = ?, shape = ?, points = ?, color = ?, enabled = ?,
                            alert_on_enter = ?, alert_on_exit = ?, speed_limit_kph = ?, updated_at = ?
                        WHERE id = ?
                        """)
                .param(value.name()).param(value.centerGcjLat()).param(value.centerGcjLng())
                .param(value.radiusMeters()).param(value.shape().wireValue())
                .param(serializePoints(value.points())).param(value.color()).param(value.enabled() ? 1 : 0)
                .param(value.alertOnEnter() ? 1 : 0).param(value.alertOnExit() ? 1 : 0)
                .param(value.speedLimitKph()).param(Timestamps.now()).param(id).update();
    }

    public int setEnabled(long id, boolean enabled) {
        return jdbc.sql("UPDATE geofence SET enabled = ?, updated_at = ? WHERE id = ?")
                .param(enabled ? 1 : 0).param(Timestamps.now()).param(id).update();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM geofence WHERE id = ?").param(id).update();
    }

    public List<String> assignedVehicleIds(long geofenceId) {
        return jdbc.sql("SELECT device_id FROM geofence_vehicle WHERE geofence_id = ? ORDER BY device_id")
                .param(geofenceId).query(String.class).list();
    }

    public void replaceVehicles(long geofenceId, List<String> deviceIds) {
        List<String> current = assignedVehicleIds(geofenceId);
        String now = Timestamps.now();
        for (String deviceId : current) {
            if (deviceIds.contains(deviceId)) continue;
            jdbc.sql("DELETE FROM geofence_vehicle WHERE geofence_id = ? AND device_id = ?")
                    .param(geofenceId).param(deviceId).update();
            jdbc.sql("DELETE FROM geofence_presence WHERE geofence_id = ? AND device_id = ?")
                    .param(geofenceId).param(deviceId).update();
        }
        for (String deviceId : deviceIds) {
            if (current.contains(deviceId)) continue;
            jdbc.sql("INSERT INTO geofence_vehicle (geofence_id, device_id, created_at) VALUES (?, ?, ?)")
                    .param(geofenceId).param(deviceId).param(now).update();
        }
    }

    public List<GeofenceCandidate> findEnabledForDevice(String deviceId) {
        return jdbc.sql("""
                        SELECT g.id, g.name, g.center_gcj_lat, g.center_gcj_lng,
                               g.radius_meters, g.shape, g.points,
                               g.alert_on_enter, g.alert_on_exit, g.speed_limit_kph
                        FROM geofence g JOIN geofence_vehicle gv ON gv.geofence_id = g.id
                        WHERE gv.device_id = ? AND g.enabled = 1 ORDER BY g.id
                        """)
                .param(deviceId)
                .query((rs, row) -> new GeofenceCandidate(
                        rs.getLong("id"), rs.getString("name"), rs.getDouble("center_gcj_lat"),
                        rs.getDouble("center_gcj_lng"), rs.getDouble("radius_meters"),
                        GeofenceShape.fromWire(rs.getString("shape")),
                        deserializePoints(rs.getString("points")),
                        rs.getBoolean("alert_on_enter"), rs.getBoolean("alert_on_exit"),
                        nullableDouble(rs, "speed_limit_kph")))
                .list();
    }

    public Optional<Boolean> presence(long geofenceId, String deviceId) {
        return jdbc.sql("SELECT inside FROM geofence_presence WHERE geofence_id = ? AND device_id = ?")
                .param(geofenceId).param(deviceId).query(Boolean.class).optional();
    }

    public void upsertPresence(long geofenceId, String deviceId, boolean inside, String at) {
        jdbc.sql("""
                        INSERT INTO geofence_presence (geofence_id, device_id, inside, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(geofence_id, device_id) DO UPDATE SET
                            inside = excluded.inside, updated_at = excluded.updated_at
                        """)
                .param(geofenceId).param(deviceId).param(inside ? 1 : 0).param(at).update();
    }

    public void deletePresence(long geofenceId) {
        jdbc.sql("DELETE FROM geofence_presence WHERE geofence_id = ?").param(geofenceId).update();
    }

    public void deleteAssignments(long geofenceId) {
        jdbc.sql("DELETE FROM geofence_vehicle WHERE geofence_id = ?").param(geofenceId).update();
    }

    private Geofence map(ResultSet rs, int row) throws SQLException {
        long id = rs.getLong("id");
        return new Geofence(id, rs.getString("name"), rs.getDouble("center_gcj_lat"),
                rs.getDouble("center_gcj_lng"), rs.getDouble("radius_meters"),
                GeofenceShape.fromWire(rs.getString("shape")),
                deserializePoints(rs.getString("points")),
                rs.getString("color"), rs.getBoolean("enabled"),
                rs.getBoolean("alert_on_enter"), rs.getBoolean("alert_on_exit"),
                nullableDouble(rs, "speed_limit_kph"), assignedVehicleIds(id),
                rs.getInt("assigned_count"), RowValues.nullableLong(rs, "tenant_id"),
                rs.getString("created_at"), rs.getString("updated_at"));
    }

    private String serializePoints(List<double[]> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(points);
        } catch (JacksonException failure) {
            throw new IllegalStateException("围栏顶点无法序列化", failure);
        }
    }

    private List<double[]> deserializePoints(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            double[][] array = mapper.readValue(json, double[][].class);
            return array == null ? List.of() : List.copyOf(Arrays.asList(array));
        } catch (JacksonException failure) {
            throw new IllegalStateException("围栏顶点无法解析", failure);
        }
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
