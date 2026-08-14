package io.github.jtconsole.repository;

import io.github.jtconsole.domain.Fleet;
import io.github.jtconsole.domain.FleetMember;
import io.github.jtconsole.domain.FleetSummary;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.security.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class FleetRepository {

    private static final String FLEET_COLUMNS = """
            f.id, f.code, f.name, f.manager, f.contact_phone, f.remark, f.tenant_id,
            f.created_at, f.updated_at
            """;

    private final JdbcClient jdbc;

    public FleetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FleetSummary> findAllSummaries(String keyword, String date, DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        String pattern = "%" + escapeLike(keyword == null ? "" : keyword) + "%";
        return jdbc.sql("""
                        WITH open_alarm AS (
                            SELECT device_id, COUNT(*) AS open_count
                            FROM alarm_event
                            WHERE status <> 'CLOSED'
                            GROUP BY device_id
                        ), today AS (
                            SELECT device_id, distance_km
                            FROM vehicle_daily_stat
                            WHERE stat_date = ?
                        )
                        SELECT f.id, f.code, f.name, f.manager, f.contact_phone, f.remark,
                               f.tenant_id, f.created_at, f.updated_at,
                            COUNT(v.device_id) AS total_vehicles,
                            COALESCE(SUM(CASE WHEN s.online = 1 THEN 1 ELSE 0 END), 0) AS online,
                            COALESCE(SUM(CASE WHEN s.online = 1 AND COALESCE(s.speed_kph, 0) > 5
                                              THEN 1 ELSE 0 END), 0) AS moving,
                            COALESCE(SUM(CASE WHEN s.online = 1 AND COALESCE(s.speed_kph, 0) <= 5
                                              THEN 1 ELSE 0 END), 0) AS idle,
                            COALESCE(SUM(open_alarm.open_count), 0) AS open_alarms,
                            ROUND(COALESCE(SUM(today.distance_km), 0), 2) AS today_distance_km
                        FROM fleet f
                        LEFT JOIN fleet_vehicle fv ON fv.fleet_id = f.id
                        LEFT JOIN vehicle v ON v.device_id = fv.device_id
                        LEFT JOIN device_status s ON s.device_id = v.device_id
                        LEFT JOIN open_alarm ON open_alarm.device_id = v.device_id
                        LEFT JOIN today ON today.device_id = v.device_id
                        WHERE (f.code LIKE ? ESCAPE '\\'
                           OR f.name LIKE ? ESCAPE '\\'
                           OR COALESCE(f.manager, '') LIKE ? ESCAPE '\\'
                           OR COALESCE(f.contact_phone, '') LIKE ? ESCAPE '\\')
                        """ + scope.tenantCondition("f") + """
                        GROUP BY f.id, f.code, f.name, f.manager, f.contact_phone, f.remark,
                                 f.tenant_id, f.created_at, f.updated_at
                        ORDER BY f.name COLLATE NOCASE, f.code, f.id
                        """)
                .param(date)
                .param(pattern).param(pattern).param(pattern).param(pattern)
                .params(scope.tenantParameters())
                .query(FleetRepository::mapSummary)
                .list();
    }

    public Optional<Fleet> findById(long id, DataScope scope) {
        if (scope.empty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + FLEET_COLUMNS + " FROM fleet f WHERE f.id = ?"
                        + scope.tenantCondition("f"))
                .param(id).params(scope.tenantParameters())
                .query(FleetRepository::mapFleet).optional();
    }

    public Optional<FleetSummary> findSummary(long id, String date, DataScope scope) {
        if (scope.empty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        WITH open_alarm AS (
                            SELECT device_id, COUNT(*) AS open_count
                            FROM alarm_event
                            WHERE status <> 'CLOSED'
                            GROUP BY device_id
                        ), today AS (
                            SELECT device_id, distance_km
                            FROM vehicle_daily_stat
                            WHERE stat_date = ?
                        )
                        SELECT f.id, f.code, f.name, f.manager, f.contact_phone, f.remark,
                               f.tenant_id, f.created_at, f.updated_at,
                            COUNT(v.device_id) AS total_vehicles,
                            COALESCE(SUM(CASE WHEN s.online = 1 THEN 1 ELSE 0 END), 0) AS online,
                            COALESCE(SUM(CASE WHEN s.online = 1 AND COALESCE(s.speed_kph, 0) > 5
                                              THEN 1 ELSE 0 END), 0) AS moving,
                            COALESCE(SUM(CASE WHEN s.online = 1 AND COALESCE(s.speed_kph, 0) <= 5
                                              THEN 1 ELSE 0 END), 0) AS idle,
                            COALESCE(SUM(open_alarm.open_count), 0) AS open_alarms,
                            ROUND(COALESCE(SUM(today.distance_km), 0), 2) AS today_distance_km
                        FROM fleet f
                        LEFT JOIN fleet_vehicle fv ON fv.fleet_id = f.id
                        LEFT JOIN vehicle v ON v.device_id = fv.device_id
                        LEFT JOIN device_status s ON s.device_id = v.device_id
                        LEFT JOIN open_alarm ON open_alarm.device_id = v.device_id
                        LEFT JOIN today ON today.device_id = v.device_id
                        WHERE f.id = ?
                        """ + scope.tenantCondition("f") + """
                        GROUP BY f.id, f.code, f.name, f.manager, f.contact_phone, f.remark,
                                 f.tenant_id, f.created_at, f.updated_at
                        """)
                .param(date).param(id).params(scope.tenantParameters())
                .query(FleetRepository::mapSummary).optional();
    }

    public List<FleetMember> findMembers(long fleetId, String date, DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        return jdbc.sql("""
                        WITH open_alarm AS (
                            SELECT device_id, COUNT(*) AS open_count
                            FROM alarm_event
                            WHERE status <> 'CLOSED'
                            GROUP BY device_id
                        ), today AS (
                            SELECT device_id, distance_km
                            FROM vehicle_daily_stat
                            WHERE stat_date = ?
                        )
                        SELECT v.device_id, v.plate_no, v.plate_color, v.brand, v.channel_count,
                               v.remark AS vehicle_remark, v.created_at AS vehicle_created_at,
                               v.updated_at AS vehicle_updated_at,
                               v.tenant_id AS vehicle_tenant_id,
                               v.department_id AS vehicle_department_id,
                               f.id AS fleet_id, f.code AS fleet_code, f.name AS fleet_name,
                               COALESCE(s.online, 0) AS online, s.speed_kph, s.last_seen_at,
                               COALESCE(open_alarm.open_count, 0) AS open_alarm_count,
                               ROUND(COALESCE(today.distance_km, 0), 2) AS today_distance_km
                        FROM fleet_vehicle fv
                        JOIN fleet f ON f.id = fv.fleet_id
                        JOIN vehicle v ON v.device_id = fv.device_id
                        LEFT JOIN device_status s ON s.device_id = v.device_id
                        LEFT JOIN open_alarm ON open_alarm.device_id = v.device_id
                        LEFT JOIN today ON today.device_id = v.device_id
                        WHERE f.id = ?
                        """ + scope.vehicleCondition("v") + """
                        ORDER BY v.plate_no COLLATE NOCASE, v.device_id
                        """)
                .param(date).param(fleetId).params(scope.parameters())
                .query(FleetRepository::mapMember).list();
    }

    public long insert(Fleet fleet) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO fleet (code, name, manager, contact_phone, remark,
                                           tenant_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(fleet.code()).param(fleet.name()).param(fleet.manager())
                .param(fleet.contactPhone()).param(fleet.remark()).param(fleet.tenantId())
                .param(fleet.createdAt()).param(fleet.updatedAt()).update(key);
        Number value = key.getKey();
        if (value == null) throw new IllegalStateException("创建车队后未返回主键");
        return value.longValue();
    }

    public int update(Fleet fleet) {
        return jdbc.sql("""
                        UPDATE fleet
                        SET code = ?, name = ?, manager = ?, contact_phone = ?, remark = ?,
                            updated_at = ?
                        WHERE id = ?
                        """)
                .param(fleet.code()).param(fleet.name()).param(fleet.manager())
                .param(fleet.contactPhone()).param(fleet.remark())
                .param(fleet.updatedAt()).param(fleet.id()).update();
    }

    /** 车队编码在租户内唯一：不同客户各自的「一队」「二队」互不冲突。 */
    public boolean codeExists(String code, Long excludedId, Long tenantId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM fleet WHERE code = ? AND COALESCE(tenant_id, 0) = ?");
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(code);
        params.add(tenantId == null ? 0L : tenantId);
        if (excludedId != null) {
            sql.append(" AND id <> ?");
            params.add(excludedId);
        }
        Integer count = jdbc.sql(sql.toString()).params(params).query(Integer.class).single();
        return count != null && count > 0;
    }

    public Optional<Long> findTenantId(long fleetId) {
        return jdbc.sql("SELECT tenant_id FROM fleet WHERE id = ?")
                .param(fleetId).query(Long.class).optional();
    }

    public int memberCount(long fleetId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM fleet_vehicle WHERE fleet_id = ?")
                .param(fleetId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public int deleteIfEmpty(long fleetId) {
        return jdbc.sql("""
                        DELETE FROM fleet
                        WHERE id = ?
                          AND NOT EXISTS (SELECT 1 FROM fleet_vehicle WHERE fleet_id = ?)
                        """)
                .param(fleetId).param(fleetId).update();
    }

    public List<String> memberIds(long fleetId) {
        return jdbc.sql("SELECT device_id FROM fleet_vehicle WHERE fleet_id = ? ORDER BY device_id")
                .param(fleetId).query(String.class).list();
    }

    public void removeMember(long fleetId, String deviceId) {
        jdbc.sql("DELETE FROM fleet_vehicle WHERE fleet_id = ? AND device_id = ?")
                .param(fleetId).param(deviceId).update();
    }

    public void assign(long fleetId, String deviceId, String assignedAt) {
        jdbc.sql("""
                        INSERT INTO fleet_vehicle (device_id, fleet_id, assigned_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT(device_id) DO UPDATE SET
                            fleet_id = excluded.fleet_id,
                            assigned_at = CASE
                                WHEN fleet_vehicle.fleet_id = excluded.fleet_id
                                THEN fleet_vehicle.assigned_at ELSE excluded.assigned_at END
                        """)
                .param(deviceId).param(fleetId).param(assignedAt).update();
    }

    private static FleetSummary mapSummary(ResultSet rs, int row) throws SQLException {
        Fleet fleet = mapFleet(rs, row);
        int total = rs.getInt("total_vehicles");
        int online = rs.getInt("online");
        return new FleetSummary(fleet, total, online, rs.getInt("moving"), rs.getInt("idle"),
                total - online, rs.getLong("open_alarms"), rs.getDouble("today_distance_km"));
    }

    private static Fleet mapFleet(ResultSet rs, int row) throws SQLException {
        return new Fleet(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("manager"), rs.getString("contact_phone"), rs.getString("remark"),
                RowValues.nullableLong(rs, "tenant_id"),
                rs.getString("created_at"), rs.getString("updated_at"));
    }

    private static FleetMember mapMember(ResultSet rs, int row) throws SQLException {
        Vehicle vehicle = new Vehicle(rs.getString("device_id"), rs.getString("plate_no"),
                rs.getString("plate_color"), rs.getString("brand"), rs.getInt("channel_count"),
                rs.getString("vehicle_remark"),
                RowValues.nullableLong(rs, "vehicle_tenant_id"),
                RowValues.nullableLong(rs, "vehicle_department_id"),
                rs.getString("vehicle_created_at"),
                rs.getString("vehicle_updated_at"));
        Fleet.Summary fleet = new Fleet.Summary(rs.getLong("fleet_id"),
                rs.getString("fleet_code"), rs.getString("fleet_name"));
        return new FleetMember(vehicle, fleet, rs.getBoolean("online"),
                nullableDouble(rs, "speed_kph"), rs.getString("last_seen_at"),
                rs.getLong("open_alarm_count"), rs.getDouble("today_distance_km"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
