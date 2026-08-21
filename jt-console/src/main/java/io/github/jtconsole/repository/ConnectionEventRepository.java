package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.ConnectionEvent;
import io.github.jtconsole.security.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectionEventRepository {
    private static final String COLUMNS = """
            id, event_id AS eventId, device_id AS deviceId, tenant_id AS tenantId,
            kind, reason_code AS reasonCode, reason, remote_addr AS remoteAddr,
            repeat_count AS repeatCount, event_time AS eventTime, received_at AS receivedAt
            """;

    private final JdbcClient jdbc;

    public ConnectionEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIgnore(ConnectionEvent event) {
        return jdbc.sql("""
                INSERT OR IGNORE INTO connection_event
                    (event_id, device_id, tenant_id, kind, reason_code, reason,
                     remote_addr, repeat_count, event_time, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(event.eventId()).param(event.deviceId()).param(event.tenantId())
                .param(event.kind()).param(event.reasonCode()).param(event.reason())
                .param(event.remoteAddr()).param(event.repeatCount())
                .param(event.eventTime()).param(event.receivedAt()).update() > 0;
    }

    /** 平台管理员可查未建档 NULL；租户范围必须同时命中车辆档案，避免探测未建档/跨租户设备。 */
    public List<ConnectionEvent> findByDevice(
            String deviceId, String start, String end, int limit, DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM connection_event WHERE device_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(deviceId);
        if (scope.isPlatform()) {
            if (scope.tenantId() != null) {
                sql.append(" AND tenant_id = ?");
                params.add(scope.tenantId());
            }
        } else {
            sql.append(" AND tenant_id = ? AND device_id IN (SELECT device_id FROM vehicle WHERE 1 = 1")
                    .append(scope.vehicleCondition("")).append(')');
            params.add(scope.tenantId());
            params.addAll(scope.parameters());
        }
        sql.append(" AND event_time >= ? AND event_time <= ? ORDER BY event_time DESC, id DESC LIMIT ?");
        params.add(lower(start));
        params.add(upper(end));
        params.add(Math.clamp(limit, 1, 500));
        return jdbc.sql(sql.toString()).params(params).query(ConnectionEventRepository::map).list();
    }

    /** 过去窗口内按设备统计注册拒绝，供今日要点检测器使用。 */
    public List<RegistrationFailure> countRegistrationFailures(
            String from, String to, DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT device_id AS deviceId, SUM(repeat_count) AS count
                FROM connection_event
                WHERE kind = 'REGISTER_RESULT' AND reason_code <> 0
                  AND event_time >= ? AND event_time <= ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(lower(from));
        params.add(upper(to));
        if (scope.isPlatform()) {
            if (scope.tenantId() != null) {
                sql.append(" AND tenant_id = ?");
                params.add(scope.tenantId());
            }
        } else {
            sql.append(" AND tenant_id = ? AND device_id IN (SELECT device_id FROM vehicle WHERE 1 = 1")
                    .append(scope.vehicleCondition("")).append(')');
            params.add(scope.tenantId());
            params.addAll(scope.parameters());
        }
        sql.append(" GROUP BY device_id HAVING SUM(repeat_count) >= 3 ORDER BY count DESC");
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, row) -> new RegistrationFailure(rs.getString("deviceId"), rs.getInt("count")))
                .list();
    }

    public int deleteOlderThan(Instant cutoff, int batchSize) {
        return jdbc.sql("""
                DELETE FROM connection_event WHERE id IN (
                    SELECT id FROM connection_event WHERE event_time < ? ORDER BY id LIMIT ?
                )
                """).param(Timestamps.of(cutoff)).param(batchSize).update();
    }

    private static String lower(String value) {
        String normalized = TimeBounds.lower(value);
        return normalized == null ? "0000-01-01T00:00:00.000+08:00" : normalized;
    }

    private static String upper(String value) {
        String normalized = TimeBounds.upper(value);
        return normalized == null ? "9999-12-31T23:59:59.999+08:00" : normalized;
    }

    public record RegistrationFailure(String deviceId, int count) {}

    private static ConnectionEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new ConnectionEvent(rs.getLong("id"), rs.getString("eventId"),
                rs.getString("deviceId"), RowValues.nullableLong(rs, "tenantId"),
                rs.getString("kind"), RowValues.nullableInt(rs, "reasonCode"),
                rs.getString("reason"), rs.getString("remoteAddr"), rs.getInt("repeatCount"),
                rs.getString("eventTime"), rs.getString("receivedAt"));
    }
}
