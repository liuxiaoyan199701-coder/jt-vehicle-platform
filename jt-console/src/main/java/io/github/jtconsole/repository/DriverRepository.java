package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Driver;
import io.github.jtconsole.domain.DriverIdentityEvent;
import io.github.jtconsole.domain.DriverSession;
import io.github.jtconsole.security.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 司机档案、0702 身份事件与驾驶区间。
 *
 * <p>司机是租户级实体，租户硬隔离 + 部门软范围（沿用车辆语义）；所有查询必传 DataScope。
 */
@Repository
public class DriverRepository {

    private final JdbcClient jdbc;

    public DriverRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 司机档案 ----------------

    public List<Driver> search(String keyword, Long departmentId, DataScope scope, int page, int pageSize) {
        if (scope.empty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                        SELECT id, name, id_card, license_no, institution, license_valid_period,
                               phone, remark, department_id, tenant_id, created_at, updated_at
                        FROM driver WHERE 1 = 1
                        """);
        sql.append(scope.vehicleCondition("driver"));
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE ? OR license_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }
        if (departmentId != null) {
            sql.append(" AND department_id = ?");
            params.add(departmentId);
        }
        sql.append(" ORDER BY name, id LIMIT ? OFFSET ?");
        params.addAll(scope.parameters());
        params.add(pageSize);
        params.add(Math.max(0, page - 1) * pageSize);
        return jdbc.sql(sql.toString()).params(params).query(DriverRepository::mapDriver).list();
    }

    public long count(String keyword, Long departmentId, DataScope scope) {
        if (scope.empty()) {
            return 0;
        }
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM driver WHERE 1 = 1");
        sql.append(scope.vehicleCondition("driver"));
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE ? OR license_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }
        if (departmentId != null) {
            sql.append(" AND department_id = ?");
            params.add(departmentId);
        }
        params.addAll(scope.parameters());
        Number value = jdbc.sql(sql.toString()).params(params).query(Number.class).single();
        return value == null ? 0 : value.longValue();
    }

    public Optional<Driver> findById(long id, DataScope scope) {
        if (scope.empty()) {
            return Optional.empty();
        }
        List<Object> params = new ArrayList<>();
        params.add(id);
        params.addAll(scope.parameters());
        return jdbc.sql("""
                        SELECT id, name, id_card, license_no, institution, license_valid_period,
                               phone, remark, department_id, tenant_id, created_at, updated_at
                        FROM driver WHERE id = ?
                        """ + scope.vehicleCondition("driver"))
                .params(params).query(DriverRepository::mapDriver).optional();
    }

    public Optional<Driver> findByLicenseNo(String licenseNo, DataScope scope) {
        if (scope.empty() || licenseNo == null || licenseNo.isBlank()) {
            return Optional.empty();
        }
        List<Object> params = new ArrayList<>();
        params.add(licenseNo.trim());
        params.addAll(scope.parameters());
        return jdbc.sql("""
                        SELECT id, name, id_card, license_no, institution, license_valid_period,
                               phone, remark, department_id, tenant_id, created_at, updated_at
                        FROM driver WHERE license_no = ?
                        """ + scope.vehicleCondition("driver"))
                .params(params).query(DriverRepository::mapDriver).optional();
    }

    public long insert(Driver value) {
        String now = Timestamps.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO driver (tenant_id, department_id, name, id_card, license_no,
                            institution, license_valid_period, phone, remark, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(value.tenantId()).param(value.departmentId()).param(value.name())
                .param(value.idCard()).param(value.licenseNo()).param(value.institution())
                .param(value.licenseValidPeriod()).param(value.phone()).param(value.remark())
                .param(now).param(now).update(key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("创建司机后未返回主键");
        return id.longValue();
    }

    public int update(long id, Driver value) {
        return jdbc.sql("""
                        UPDATE driver SET department_id = ?, name = ?, id_card = ?, license_no = ?,
                            institution = ?, license_valid_period = ?, phone = ?, remark = ?, updated_at = ?
                        WHERE id = ?
                        """)
                .param(value.departmentId()).param(value.name()).param(value.idCard())
                .param(value.licenseNo()).param(value.institution()).param(value.licenseValidPeriod())
                .param(value.phone()).param(value.remark()).param(Timestamps.now()).param(id).update();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM driver WHERE id = ?").param(id).update();
    }

    /** 从业资格证在指定日期（含）前到期的司机，供到期提醒使用。 */
    public List<Driver> findExpiringBy(String validPeriodUpperBound, DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(validPeriodUpperBound);
        params.addAll(scope.parameters());
        return jdbc.sql("""
                        SELECT id, name, id_card, license_no, institution, license_valid_period,
                               phone, remark, department_id, tenant_id, created_at, updated_at
                        FROM driver WHERE license_valid_period IS NOT NULL
                          AND license_valid_period <> ''
                          AND license_valid_period <= ?
                        """ + scope.vehicleCondition("driver") + " ORDER BY license_valid_period, id")
                .params(params).query(DriverRepository::mapDriver).list();
    }

    // ---------------- 0702 身份事件 ----------------

    public boolean insertIdentityEvent(DriverIdentityEvent event) {
        return jdbc.sql("""
                        INSERT OR IGNORE INTO driver_identity_event (
                            event_id, device_id, status, card_status, name, license_no,
                            institution, license_valid_period, id_card, driver_id,
                            device_time, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(event.eventId()).param(event.deviceId()).param(event.status())
                .param(event.cardStatus()).param(event.name()).param(event.licenseNo())
                .param(event.institution()).param(event.licenseValidPeriod()).param(event.idCard())
                .param(event.driverId()).param(event.deviceTime()).param(event.receivedAt())
                .update() == 1;
    }

    public List<DriverIdentityEvent> searchIdentityEvents(
            String deviceId, Boolean unmatched, Boolean failed, String start, String end,
            DataScope scope, int page, int pageSize) {
        if (scope.empty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                        SELECT id, event_id, device_id, status, card_status, name, license_no,
                               institution, license_valid_period, id_card, driver_id,
                               device_time, received_at
                        FROM driver_identity_event WHERE 1 = 1
                        """);
        sql.append(scope.deviceCondition("device_id"));
        if (deviceId != null && !deviceId.isBlank()) {
            sql.append(" AND device_id = ?");
            params.add(deviceId.trim());
        }
        if (Boolean.TRUE.equals(unmatched)) {
            sql.append(" AND driver_id IS NULL AND card_status = 0");
        }
        if (Boolean.TRUE.equals(failed)) {
            sql.append(" AND card_status <> 0");
        }
        if (TimeBounds.lower(start) != null) {
            sql.append(" AND device_time >= ?");
            params.add(TimeBounds.lower(start));
        }
        if (TimeBounds.upper(end) != null) {
            sql.append(" AND device_time <= ?");
            params.add(TimeBounds.upper(end));
        }
        sql.append(" ORDER BY device_time DESC, id DESC LIMIT ? OFFSET ?");
        params.addAll(scope.parameters());
        params.add(pageSize);
        params.add(Math.max(0, page - 1) * pageSize);
        return jdbc.sql(sql.toString()).params(params)
                .query(DriverRepository::mapIdentityEvent).list();
    }

    // ---------------- 驾驶区间 ----------------

    public Optional<DriverSession> findOpenSession(String deviceId) {
        return jdbc.sql("""
                        SELECT id, device_id, driver_id, driver_name, license_no,
                               started_at, ended_at, source
                        FROM vehicle_driver_session WHERE device_id = ? AND ended_at IS NULL
                        """)
                .param(deviceId).query(DriverRepository::mapSession).optional();
    }

    public void closeOpenSession(String deviceId, String endedAt) {
        jdbc.sql("""
                        UPDATE vehicle_driver_session SET ended_at = ?, updated_at = ?
                        WHERE device_id = ? AND ended_at IS NULL
                        """)
                .param(endedAt).param(Timestamps.now()).param(deviceId).update();
    }

    public void openSession(String deviceId, Long driverId, String driverName,
                            String licenseNo, String startedAt, String source) {
        jdbc.sql("""
                        INSERT INTO vehicle_driver_session (
                            device_id, driver_id, driver_name, license_no,
                            started_at, ended_at, source, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?)
                        """)
                .param(deviceId).param(driverId).param(driverName).param(licenseNo)
                .param(startedAt).param(source).param(Timestamps.now()).param(Timestamps.now()).update();
    }

    public List<DriverSession> findSessionsByDriver(long driverId, DataScope scope, int limit) {
        if (scope.empty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(driverId);
        params.addAll(scope.parameters());
        params.add(limit);
        return jdbc.sql("""
                        SELECT s.id, s.device_id, s.driver_id, s.driver_name, s.license_no,
                               s.started_at, s.ended_at, s.source
                        FROM vehicle_driver_session s
                        WHERE s.driver_id = ?
                        """ + scope.deviceCondition("s.device_id")
                        + " ORDER BY s.started_at DESC LIMIT ?")
                .params(params).query(DriverRepository::mapSession).list();
    }

    public Optional<DriverSession> findCurrentSession(String deviceId) {
        return findOpenSession(deviceId);
    }

    private static Driver mapDriver(ResultSet rs, int row) throws SQLException {
        return new Driver(rs.getLong("id"), rs.getString("name"), rs.getString("id_card"),
                rs.getString("license_no"), rs.getString("institution"),
                rs.getString("license_valid_period"), rs.getString("phone"), rs.getString("remark"),
                RowValues.nullableLong(rs, "department_id"), RowValues.nullableLong(rs, "tenant_id"),
                rs.getString("created_at"), rs.getString("updated_at"));
    }

    private static DriverIdentityEvent mapIdentityEvent(ResultSet rs, int row) throws SQLException {
        return new DriverIdentityEvent(rs.getLong("id"), rs.getString("event_id"),
                rs.getString("device_id"), rs.getInt("status"), rs.getInt("card_status"),
                rs.getString("name"), rs.getString("license_no"), rs.getString("institution"),
                rs.getString("license_valid_period"), rs.getString("id_card"),
                RowValues.nullableLong(rs, "driver_id"), rs.getString("device_time"),
                rs.getString("received_at"));
    }

    private static DriverSession mapSession(ResultSet rs, int row) throws SQLException {
        return new DriverSession(rs.getLong("id"), rs.getString("device_id"),
                RowValues.nullableLong(rs, "driver_id"), rs.getString("driver_name"),
                rs.getString("license_no"), rs.getString("started_at"), rs.getString("ended_at"),
                rs.getString("source"));
    }
}
