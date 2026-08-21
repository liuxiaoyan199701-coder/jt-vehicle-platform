package io.github.jtconsole.repository;

import io.github.jtconsole.domain.AuditEntry;
import io.github.jtconsole.security.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志。刻意不提供 update 与 delete 单条记录的方法——审计一旦可改就不再是审计；
 * 只有按保留期批量清理是允许的删除路径。
 */
@Repository
public class AuditRepository {

    private static final String COLUMNS = """
            id, occurred_at, tenant_id, account_id, username, action, resource_type, resource_id,
            method, path, detail, source_ip, result, status_code, duration_ms
            """;

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量写入。审计由单写线程调用，批量提交避免与业务请求争抢 SQLite 写锁。 */
    @Transactional
    public void insertBatch(List<AuditEntry> entries) {
        for (AuditEntry entry : entries) {
            jdbc.sql("""
                            INSERT INTO audit_log (occurred_at, tenant_id, account_id, username,
                                                   action, resource_type, resource_id, method,
                                                   path, detail, source_ip, result, status_code,
                                                   duration_ms)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .param(entry.occurredAt()).param(entry.tenantId()).param(entry.accountId())
                    .param(entry.username()).param(entry.action()).param(entry.resourceType())
                    .param(entry.resourceId()).param(entry.method()).param(entry.path())
                    .param(entry.detail()).param(entry.sourceIp()).param(entry.result())
                    .param(entry.statusCode()).param(entry.durationMs())
                    .update();
        }
    }

    public List<AuditEntry> search(AuditQuery query) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM audit_log WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, query);
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(query.size());
        params.add(Math.max(0, (query.page() - 1) * query.size()));
        return jdbc.sql(sql.toString()).params(params).query(AuditRepository::map).list();
    }

    /** 体检只读取指定设备的只读审计事实，租户条件由 DataScope 强制加入。 */
    public List<AuditEntryView> findDeviceActions(
            String deviceId, List<String> actions, String from, String to, DataScope scope) {
        if (scope.empty() || actions.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT occurred_at, action, result, path, detail FROM audit_log WHERE resource_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(deviceId);
        if (!scope.isPlatform()) {
            sql.append(" AND tenant_id = ?");
            params.add(scope.tenantId());
        } else if (scope.tenantId() != null) {
            sql.append(" AND tenant_id = ?");
            params.add(scope.tenantId());
        }
        sql.append(" AND action IN (")
                .append(String.join(", ", java.util.Collections.nCopies(actions.size(), "?"))).append(')');
        params.addAll(actions);
        if (from != null && !from.isBlank()) {
            sql.append(" AND occurred_at >= ?");
            params.add(TimeBounds.lower(from));
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND occurred_at <= ?");
            params.add(TimeBounds.upper(to));
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT 100");
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, row) -> new AuditEntryView(
                        rs.getString("occurred_at"), rs.getString("action"), rs.getString("result"),
                        rs.getString("path"), rs.getString("detail")))
                .list();
    }

    public int count(AuditQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM audit_log WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, query);
        Integer count = jdbc.sql(sql.toString()).params(params).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    /** 分批删除超期记录，避免一条长事务阻塞业务写入。返回本批删除条数。 */
    public int deleteOlderThan(String cutoff, int batchSize) {
        return jdbc.sql("""
                        DELETE FROM audit_log WHERE id IN (
                            SELECT id FROM audit_log WHERE occurred_at < ?
                            ORDER BY id LIMIT ?
                        )
                        """)
                .param(cutoff).param(batchSize)
                .update();
    }

    private static void appendFilters(StringBuilder sql, List<Object> params, AuditQuery query) {
        if (query.tenantId() != null) {
            sql.append(" AND tenant_id = ?");
            params.add(query.tenantId());
        }
        if (notBlank(query.username())) {
            sql.append(" AND username = ?");
            params.add(query.username().trim());
        }
        if (notBlank(query.action())) {
            sql.append(" AND action = ?");
            params.add(query.action().trim());
        }
        if (notBlank(query.resourceType())) {
            sql.append(" AND resource_type = ?");
            params.add(query.resourceType().trim());
        }
        if (notBlank(query.result())) {
            sql.append(" AND result = ?");
            params.add(query.result().trim());
        }
        if (notBlank(query.from())) {
            sql.append(" AND occurred_at >= ?");
            params.add(query.from().trim());
        }
        if (notBlank(query.to())) {
            sql.append(" AND occurred_at <= ?");
            params.add(query.to().trim());
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static AuditEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEntry(
                rs.getLong("id"),
                rs.getString("occurred_at"),
                RowValues.nullableLong(rs, "tenant_id"),
                RowValues.nullableLong(rs, "account_id"),
                rs.getString("username"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("method"),
                rs.getString("path"),
                rs.getString("detail"),
                rs.getString("source_ip"),
                rs.getString("result"),
                RowValues.nullableInt(rs, "status_code"),
                RowValues.nullableInt(rs, "duration_ms"));
    }

    /**
     * 审计检索条件。{@code tenantId} 由服务层按会话强制填入，
     * MUST NOT 直接取自请求参数，否则租户可以查到别人的审计。
     */
    public record AuditEntryView(
            String occurredAt, String action, String result, String path, String detail) {}

    public record AuditQuery(
            Long tenantId,
            String username,
            String action,
            String resourceType,
            String result,
            String from,
            String to,
            int page,
            int size) {

        public AuditQuery {
            page = Math.max(1, page);
            size = Math.clamp(size, 1, 200);
        }
    }
}
