package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.AlarmLevel;
import io.github.jtconsole.domain.AlarmRule;
import io.github.jtconsole.domain.AlarmRuleCandidate;
import io.github.jtconsole.domain.AlarmRuleType;
import io.github.jtconsole.security.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AlarmRuleRepository {

    private static final String SELECT = """
            SELECT r.id, r.name, r.type, r.threshold_kph, r.duration_minutes, r.level,
                   r.enabled, r.tenant_id, r.created_at, r.updated_at,
                   (SELECT COUNT(*) FROM alarm_rule_vehicle rv WHERE rv.rule_id = r.id) assigned_count
            FROM alarm_rule r
            """;
    private final JdbcClient jdbc;

    public AlarmRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AlarmRule> findAll(DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE 1 = 1" + scope.tenantCondition("r")
                        + " ORDER BY r.name, r.id")
                .params(scope.tenantParameters()).query(this::map).list();
    }

    public Optional<AlarmRule> findById(long id, DataScope scope) {
        if (scope.empty()) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " WHERE r.id = ?" + scope.tenantCondition("r"))
                .param(id).params(scope.tenantParameters()).query(this::map).optional();
    }

    public long insert(AlarmRule value) {
        String now = Timestamps.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO alarm_rule (
                            name, type, threshold_kph, duration_minutes, level, enabled,
                            tenant_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(value.name()).param(value.type().name()).param(value.thresholdKph())
                .param(value.durationMinutes()).param(value.level().name())
                .param(value.enabled() ? 1 : 0).param(value.tenantId())
                .param(now).param(now).update(key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("创建告警规则后未返回主键");
        return id.longValue();
    }

    public int update(long id, AlarmRule value) {
        return jdbc.sql("""
                        UPDATE alarm_rule SET name = ?, type = ?, threshold_kph = ?,
                            duration_minutes = ?, level = ?, enabled = ?, updated_at = ?
                        WHERE id = ?
                        """)
                .param(value.name()).param(value.type().name()).param(value.thresholdKph())
                .param(value.durationMinutes()).param(value.level().name())
                .param(value.enabled() ? 1 : 0).param(Timestamps.now()).param(id).update();
    }

    public int setEnabled(long id, boolean enabled) {
        return jdbc.sql("UPDATE alarm_rule SET enabled = ?, updated_at = ? WHERE id = ?")
                .param(enabled ? 1 : 0).param(Timestamps.now()).param(id).update();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM alarm_rule WHERE id = ?").param(id).update();
    }

    public List<String> assignedVehicleIds(long ruleId) {
        return jdbc.sql("SELECT device_id FROM alarm_rule_vehicle WHERE rule_id = ? ORDER BY device_id")
                .param(ruleId).query(String.class).list();
    }

    public void replaceVehicles(long ruleId, List<String> deviceIds) {
        List<String> current = assignedVehicleIds(ruleId);
        String now = Timestamps.now();
        for (String deviceId : current) {
            if (deviceIds.contains(deviceId)) continue;
            jdbc.sql("DELETE FROM alarm_rule_vehicle WHERE rule_id = ? AND device_id = ?")
                    .param(ruleId).param(deviceId).update();
            jdbc.sql("DELETE FROM alarm_rule_state WHERE rule_id = ? AND device_id = ?")
                    .param(ruleId).param(deviceId).update();
        }
        for (String deviceId : deviceIds) {
            if (current.contains(deviceId)) continue;
            jdbc.sql("INSERT INTO alarm_rule_vehicle (rule_id, device_id, created_at) VALUES (?, ?, ?)")
                    .param(ruleId).param(deviceId).param(now).update();
        }
    }

    public void deleteAssignments(long ruleId) {
        jdbc.sql("DELETE FROM alarm_rule_vehicle WHERE rule_id = ?").param(ruleId).update();
    }

    public List<AlarmRuleCandidate> findEnabledForDevice(String deviceId) {
        return jdbc.sql("""
                        SELECT r.id, r.name, r.type, r.threshold_kph, r.duration_minutes, r.level
                        FROM alarm_rule r JOIN alarm_rule_vehicle rv ON rv.rule_id = r.id
                        WHERE rv.device_id = ? AND r.enabled = 1 ORDER BY r.id
                        """)
                .param(deviceId)
                .query((rs, row) -> new AlarmRuleCandidate(
                        rs.getLong("id"), rs.getString("name"),
                        AlarmRuleType.fromWire(rs.getString("type")),
                        rs.getDouble("threshold_kph"), rs.getInt("duration_minutes"),
                        AlarmLevel.valueOf(rs.getString("level"))))
                .list();
    }

    public Optional<String> findWindowStart(long ruleId, String deviceId) {
        return jdbc.sql("SELECT window_start_at FROM alarm_rule_state WHERE rule_id = ? AND device_id = ?")
                .param(ruleId).param(deviceId).query(String.class).optional();
    }

    public void upsertWindowStart(long ruleId, String deviceId, String at) {
        jdbc.sql("""
                        INSERT INTO alarm_rule_state (rule_id, device_id, window_start_at, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(rule_id, device_id) DO UPDATE SET
                            window_start_at = excluded.window_start_at, updated_at = excluded.updated_at
                        """)
                .param(ruleId).param(deviceId).param(at).param(Timestamps.now()).update();
    }

    public void deleteWindow(long ruleId, String deviceId) {
        jdbc.sql("DELETE FROM alarm_rule_state WHERE rule_id = ? AND device_id = ?")
                .param(ruleId).param(deviceId).update();
    }

    public void deleteWindows(long ruleId) {
        jdbc.sql("DELETE FROM alarm_rule_state WHERE rule_id = ?").param(ruleId).update();
    }

    private AlarmRule map(ResultSet rs, int row) throws SQLException {
        long id = rs.getLong("id");
        return new AlarmRule(id, rs.getString("name"), AlarmRuleType.fromWire(rs.getString("type")),
                rs.getDouble("threshold_kph"), rs.getInt("duration_minutes"),
                AlarmLevel.valueOf(rs.getString("level")), rs.getBoolean("enabled"),
                assignedVehicleIds(id), rs.getInt("assigned_count"),
                RowValues.nullableLong(rs, "tenant_id"),
                rs.getString("created_at"), rs.getString("updated_at"));
    }
}
