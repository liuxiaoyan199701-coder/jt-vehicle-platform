package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Notice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 主动通知的读写。
 *
 * <p>判重靠数据库而不是内存窗口：简报调度可能因重启而重跑，内存窗口一重启就全忘了，
 * 那会在每次发版后把所有还在持续的问题重新通知一遍。
 */
@Repository
public class NoticeRepository {

    private static final String COLUMNS = """
            id, tenant_id AS tenantId, dedup_key AS dedupKey, category, severity, summary,
            facts, device_ids AS deviceIds, link_route AS linkRoute, link_query AS linkQuery,
            link_label AS linkLabel, created_at AS createdAt
            """;

    private final JdbcClient jdbc;

    public NoticeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 落一条通知，返回自增主键。
     *
     * <p>用 {@code INSERT OR IGNORE}：唯一约束 {@code (tenant_id, dedup_key, created_at)}
     * 只挡同一毫秒内的重复写入，撞上时静默跳过而不是抛异常——重复写入是竞态的良性结果，
     * 让简报生成因此失败得不偿失。
     *
     * @return 新行主键；被唯一约束挡下时为空
     */
    public Optional<Long> insert(Notice notice) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        int inserted = jdbc.sql("""
                INSERT OR IGNORE INTO notice
                    (tenant_id, dedup_key, category, severity, summary, facts, device_ids,
                     link_route, link_query, link_label, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(notice.tenantId()).param(notice.dedupKey()).param(notice.category())
                .param(notice.severity()).param(notice.summary()).param(notice.facts())
                .param(notice.deviceIds()).param(notice.linkRoute()).param(notice.linkQuery())
                .param(notice.linkLabel()).param(notice.createdAt())
                .update(key);
        // 主键取自本次语句的 generated keys，不查 last_insert_rowid()：
        // 那是**连接级**的状态，而 JdbcClient 每条语句都可能拿到池里的另一条连接。
        Number value = inserted == 0 ? null : key.getKey();
        return value == null ? Optional.empty() : Optional.of(value.longValue());
    }

    /** 该去重键最近一条。抑制判定的唯一输入。 */
    public Optional<Notice> findLatestByDedupKey(long tenantId, String dedupKey) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM notice WHERE tenant_id = ? AND dedup_key = ?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """)
                .param(tenantId).param(dedupKey)
                .query(NoticeRepository::map)
                .optional();
    }

    /** 按租户翻页，最近优先。 */
    public List<Notice> findByTenant(long tenantId, int offset, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM notice WHERE tenant_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """)
                .param(tenantId).param(Math.max(1, limit)).param(Math.max(0, offset))
                .query(NoticeRepository::map)
                .list();
    }

    public long countByTenant(long tenantId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM notice WHERE tenant_id = ?")
                .param(tenantId).query(Long.class).single();
        return count == null ? 0 : count;
    }

    /**
     * 该账号在本租户的未读条数。
     *
     * <p>反连接而不是 {@code NOT IN}：{@code notice_read} 的主键
     * {@code (notice_id, account_id)} 正好覆盖这次探测，翻页与计数走同一套索引。
     */
    public long countUnread(long tenantId, long accountId) {
        Long count = jdbc.sql("""
                SELECT COUNT(*) FROM notice n
                WHERE n.tenant_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM notice_read r
                      WHERE r.notice_id = n.id AND r.account_id = ?)
                """)
                .param(tenantId).param(accountId).query(Long.class).single();
        return count == null ? 0 : count;
    }

    /** 单条读取，供标记已读前的归属校验使用。 */
    public Optional<Notice> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM notice WHERE id = ?")
                .param(id).query(NoticeRepository::map).optional();
    }

    /**
     * 分批删除过期通知，连带删掉它们的已读记录。
     *
     * <p>先删已读再删通知：反过来做，中途失败会留下指向不存在通知的孤儿已读行。
     *
     * @return 本批删掉的通知条数；小于 batchSize 即表示已删到头
     */
    public int deleteOlderThan(Instant cutoff, int batchSize) {
        String cutoffAt = Timestamps.of(cutoff);
        int limit = Math.max(1, batchSize);
        List<Long> expiring = jdbc.sql("""
                SELECT id FROM notice WHERE created_at < ? ORDER BY id LIMIT ?
                """).param(cutoffAt).param(limit).query(Long.class).list();
        if (expiring.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(expiring.size(), "?"));
        jdbc.sql("DELETE FROM notice_read WHERE notice_id IN (" + placeholders + ")")
                .params(expiring.stream().map(Object.class::cast).toList()).update();
        return jdbc.sql("DELETE FROM notice WHERE id IN (" + placeholders + ")")
                .params(expiring.stream().map(Object.class::cast).toList()).update();
    }

    private static Notice map(ResultSet rs, int rowNum) throws SQLException {
        return new Notice(
                rs.getLong("id"),
                rs.getLong("tenantId"),
                rs.getString("dedupKey"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getString("summary"),
                rs.getString("facts"),
                rs.getString("deviceIds"),
                rs.getString("linkRoute"),
                rs.getString("linkQuery"),
                rs.getString("linkLabel"),
                rs.getString("createdAt"));
    }
}
