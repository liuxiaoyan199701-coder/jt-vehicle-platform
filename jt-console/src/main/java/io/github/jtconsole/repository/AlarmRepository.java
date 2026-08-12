package io.github.jtconsole.repository;

import io.github.jtconsole.domain.AlarmDefinition;
import io.github.jtconsole.domain.AlarmEvent;
import io.github.jtconsole.domain.AlarmLevel;
import io.github.jtconsole.domain.AlarmPage;
import io.github.jtconsole.domain.AlarmSource;
import io.github.jtconsole.domain.AlarmStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AlarmRepository {

    private static final String SELECT_COLUMNS = """
            SELECT a.id, a.device_id, v.plate_no, a.type, a.title, a.source, a.level, a.status,
                   a.occurred_at, a.last_occurred_at, a.gcj_lat, a.gcj_lng,
                   a.geofence_id, a.geofence_name, a.acknowledged_at, a.acknowledged_by,
                   a.acknowledge_note, a.closed_at, a.closed_by, a.close_note
            FROM alarm_event a LEFT JOIN vehicle v ON v.device_id = a.device_id
            """;
    private static final RowMapper<AlarmEvent> MAPPER = AlarmRepository::mapAlarm;

    private final JdbcClient jdbc;

    public AlarmRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ConditionState> findCondition(String deviceId, AlarmSource source, String key) {
        return jdbc.sql("""
                        SELECT active, alarm_event_id, geofence_id, last_seen_at
                        FROM alarm_condition_state
                        WHERE device_id = ? AND source = ? AND alarm_key = ?
                        """)
                .param(deviceId).param(source.name()).param(key)
                .query((rs, row) -> new ConditionState(
                        rs.getBoolean("active"), nullableLong(rs, "alarm_event_id"),
                        nullableLong(rs, "geofence_id"), rs.getString("last_seen_at")))
                .optional();
    }

    public long create(
            String deviceId,
            AlarmDefinition definition,
            AlarmSource source,
            String occurredAt,
            Double gcjLat,
            Double gcjLng,
            Long geofenceId,
            String geofenceName) {
        String now = Instant.now().toString();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO alarm_event (
                            device_id, type, title, source, level, status, occurred_at,
                            last_occurred_at, gcj_lat, gcj_lng, geofence_id, geofence_name,
                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(deviceId).param(definition.type()).param(definition.title())
                .param(source.name()).param(definition.level().name())
                .param(occurredAt).param(occurredAt).param(gcjLat).param(gcjLng)
                .param(geofenceId).param(geofenceName).param(now).param(now)
                .update(key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("创建告警后未返回主键");
        }
        return value.longValue();
    }

    public void activateCondition(
            String deviceId, AlarmSource source, String key, long alarmEventId,
            Long geofenceId, String occurredAt) {
        jdbc.sql("""
                        INSERT INTO alarm_condition_state (
                            device_id, source, alarm_key, active, alarm_event_id,
                            geofence_id, last_seen_at, updated_at)
                        VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                        ON CONFLICT(device_id, source, alarm_key) DO UPDATE SET
                            active = 1, alarm_event_id = excluded.alarm_event_id,
                            geofence_id = excluded.geofence_id,
                            last_seen_at = excluded.last_seen_at, updated_at = excluded.updated_at
                        """)
                .param(deviceId).param(source.name()).param(key).param(alarmEventId)
                .param(geofenceId).param(occurredAt).param(Instant.now().toString()).update();
    }

    public void touchCondition(
            String deviceId, AlarmSource source, String key, long alarmEventId, String occurredAt) {
        String now = Instant.now().toString();
        jdbc.sql("""
                        UPDATE alarm_condition_state
                        SET last_seen_at = CASE
                                WHEN last_seen_at IS NULL
                                  OR julianday(last_seen_at) < julianday(?) THEN ?
                                ELSE last_seen_at END,
                            updated_at = ?
                        WHERE device_id = ? AND source = ? AND alarm_key = ? AND active = 1
                        """)
                .param(occurredAt).param(occurredAt).param(now)
                .param(deviceId).param(source.name()).param(key).update();
        jdbc.sql("""
                        UPDATE alarm_event
                        SET last_occurred_at = CASE
                                WHEN julianday(last_occurred_at) < julianday(?)
                                THEN ? ELSE last_occurred_at END,
                            updated_at = ?
                        WHERE id = ?
                        """)
                .param(occurredAt).param(occurredAt).param(now).param(alarmEventId).update();
    }

    public void deactivateCondition(String deviceId, AlarmSource source, String key) {
        jdbc.sql("""
                        UPDATE alarm_condition_state SET active = 0, updated_at = ?
                        WHERE device_id = ? AND source = ? AND alarm_key = ? AND active = 1
                        """)
                .param(Instant.now().toString()).param(deviceId).param(source.name()).param(key).update();
    }

    public void deactivateGeofenceConditions(long geofenceId) {
        jdbc.sql("""
                        UPDATE alarm_condition_state SET active = 0, updated_at = ?
                        WHERE geofence_id = ? AND active = 1
                        """)
                .param(Instant.now().toString()).param(geofenceId).update();
    }

    public void deleteGeofenceConditions(long geofenceId) {
        jdbc.sql("DELETE FROM alarm_condition_state WHERE geofence_id = ?")
                .param(geofenceId).update();
    }

    public void deleteGeofenceCondition(long geofenceId, String deviceId) {
        jdbc.sql("""
                        DELETE FROM alarm_condition_state
                        WHERE geofence_id = ? AND device_id = ?
                        """)
                .param(geofenceId).param(deviceId).update();
    }

    public int countActive(String deviceId) {
        return number("SELECT COUNT(*) FROM alarm_condition_state WHERE device_id = ? AND active = 1",
                List.of(deviceId)).intValue();
    }

    public Optional<AlarmEvent> findById(long id) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE a.id = ?").param(id).query(MAPPER).optional();
    }

    public AlarmPage search(AlarmFilter filter) {
        Where where = where(filter);
        long total = number("SELECT COUNT(*) FROM alarm_event a LEFT JOIN vehicle v ON v.device_id = a.device_id "
                + where.sql(), where.params()).longValue();
        int offset = (filter.page() - 1) * filter.pageSize();
        List<Object> pageParams = new ArrayList<>(where.params());
        pageParams.add(filter.pageSize());
        pageParams.add(offset);
        List<AlarmEvent> items = jdbc.sql(SELECT_COLUMNS + where.sql()
                        + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT ? OFFSET ?")
                .params(pageParams).query(MAPPER).list();
        return new AlarmPage(items, total, filter.page(), filter.pageSize());
    }

    public List<AlarmEvent> recent(int limit) {
        return jdbc.sql(SELECT_COLUMNS + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT ?")
                .param(limit).query(MAPPER).list();
    }

    public List<AlarmEvent> recentByDevice(String deviceId, int limit) {
        return jdbc.sql(SELECT_COLUMNS
                        + " WHERE a.device_id = ? ORDER BY a.occurred_at DESC, a.id DESC LIMIT ?")
                .param(deviceId).param(limit).query(MAPPER).list();
    }

    public long countOpenByDevice(String deviceId) {
        return number("SELECT COUNT(*) FROM alarm_event WHERE device_id = ? AND status <> 'CLOSED'",
                List.of(deviceId)).longValue();
    }

    public int acknowledge(long id, String note, String operator, String at) {
        return jdbc.sql("""
                        UPDATE alarm_event
                        SET status = 'ACKNOWLEDGED', acknowledged_at = ?, acknowledged_by = ?,
                            acknowledge_note = ?, updated_at = ?
                        WHERE id = ? AND status = 'OPEN'
                        """)
                .param(at).param(operator).param(note).param(at).param(id).update();
    }

    public int close(long id, String note, String operator, String at) {
        return jdbc.sql("""
                        UPDATE alarm_event
                        SET status = 'CLOSED', closed_at = ?, closed_by = ?, close_note = ?, updated_at = ?
                        WHERE id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
                        """)
                .param(at).param(operator).param(note).param(at).param(id).update();
    }

    public long countOpen() {
        return number("SELECT COUNT(*) FROM alarm_event WHERE status <> 'CLOSED'", List.of()).longValue();
    }

    public long countCriticalOpen() {
        return number("SELECT COUNT(*) FROM alarm_event WHERE status <> 'CLOSED' AND level = 'CRITICAL'",
                List.of()).longValue();
    }

    public List<LevelCount> countOpenByLevel() {
        return jdbc.sql("""
                        SELECT level, COUNT(*) AS count
                        FROM alarm_event WHERE status <> 'CLOSED'
                        GROUP BY level ORDER BY level
                        """)
                .query((rs, row) -> new LevelCount(AlarmLevel.valueOf(rs.getString("level")),
                        rs.getLong("count"))).list();
    }

    private Number number(String sql, List<?> params) {
        Number value = jdbc.sql(sql).params(params).query(Number.class).single();
        return value == null ? 0 : value;
    }

    private static Where where(AlarmFilter filter) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        add(sql, params, "a.status = ?", filter.status());
        add(sql, params, "a.level = ?", filter.level());
        add(sql, params, "a.source = ?", filter.source());
        add(sql, params, "a.device_id = ?", filter.deviceId());
        add(sql, params, "a.type = ?", filter.type());
        add(sql, params, "a.occurred_at >= ?", filter.start());
        add(sql, params, "a.occurred_at <= ?", filter.end());
        if (hasText(filter.keyword())) {
            sql.append(" AND (a.title LIKE ? OR a.type LIKE ? OR a.device_id LIKE ? OR v.plate_no LIKE ?)");
            String keyword = "%" + filter.keyword().trim() + "%";
            params.add(keyword); params.add(keyword); params.add(keyword); params.add(keyword);
        }
        return new Where(sql.toString(), params);
    }

    private static void add(StringBuilder sql, List<Object> params, String clause, Object value) {
        String actual = value instanceof Enum<?> enumeration ? enumeration.name() : value == null ? null : value.toString().trim();
        if (hasText(actual)) {
            sql.append(" AND ").append(clause);
            params.add(actual);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static AlarmEvent mapAlarm(ResultSet rs, int row) throws SQLException {
        return new AlarmEvent(
                rs.getLong("id"), rs.getString("device_id"), rs.getString("plate_no"),
                rs.getString("type"), rs.getString("title"),
                AlarmSource.valueOf(rs.getString("source")), AlarmLevel.valueOf(rs.getString("level")),
                AlarmStatus.valueOf(rs.getString("status")), rs.getString("occurred_at"),
                rs.getString("last_occurred_at"), nullableDouble(rs, "gcj_lat"),
                nullableDouble(rs, "gcj_lng"), nullableLong(rs, "geofence_id"),
                rs.getString("geofence_name"), rs.getString("acknowledged_at"),
                rs.getString("acknowledged_by"), rs.getString("acknowledge_note"),
                rs.getString("closed_at"), rs.getString("closed_by"), rs.getString("close_note"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public record ConditionState(boolean active, Long alarmEventId, Long geofenceId, String lastSeenAt) {}
    public record AlarmFilter(
            AlarmStatus status, AlarmLevel level, AlarmSource source, String deviceId,
            String type, String keyword, String start, String end, int page, int pageSize) {}
    public record LevelCount(AlarmLevel level, long count) {}
    private record Where(String sql, List<Object> params) {}
}
