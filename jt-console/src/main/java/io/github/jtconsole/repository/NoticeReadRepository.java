package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 已读状态：**每人一份**。
 *
 * <p>单独一张表而不是在 {@code notice} 上加一列，是因为同一条通知对一个租户里的多个人
 * 各自有已读状态。记在通知上就变成「谁先看了算所有人都看了」——第二个人永远看不到它。
 */
@Repository
public class NoticeReadRepository {

    /** 单条 SQL 里的绑定参数上限留够余量，超出就分片。SQLite 默认上限是 999。 */
    private static final int MAX_PARAMETERS = 500;

    private final JdbcClient jdbc;

    public NoticeReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 标记已读。幂等：重复标记不报错，也不改动首次已读时间。 */
    public void markRead(long noticeId, long accountId) {
        jdbc.sql("""
                INSERT OR IGNORE INTO notice_read (notice_id, account_id, read_at)
                VALUES (?, ?, ?)
                """)
                .param(noticeId).param(accountId).param(Timestamps.now())
                .update();
    }

    /**
     * 批量标记已读。
     *
     * <p>调用方传入的必须是**它自己可见的那些通知 id**——这里不做归属校验，
     * 一个「全部已读」若直接按租户扫表，会把部门受限账号根本看不到的通知也标掉。
     */
    public int markAllRead(long accountId, Collection<Long> noticeIds) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return 0;
        }
        String readAt = Timestamps.now();
        int marked = 0;
        for (List<Long> chunk : partition(List.copyOf(noticeIds))) {
            String rows = String.join(", ", java.util.Collections.nCopies(chunk.size(), "(?, ?, ?)"));
            List<Object> params = new java.util.ArrayList<>(chunk.size() * 3);
            for (Long noticeId : chunk) {
                params.add(noticeId);
                params.add(accountId);
                params.add(readAt);
            }
            marked += jdbc.sql(
                    "INSERT OR IGNORE INTO notice_read (notice_id, account_id, read_at) VALUES "
                            + rows)
                    .params(params).update();
        }
        return marked;
    }

    /**
     * 把一个租户的全部通知标为该账号已读。
     *
     * <p>只给**范围覆盖整个租户**的调用者用：一条集合语句，条数不受清单长度限制。
     * 部门受限的账号必须走 {@link #markAllRead}，否则会把他根本看不到的通知也标掉——
     * 那些通知随后对他永远是已读，而他从头到尾没见过它们。
     */
    public int markAllReadInTenant(long tenantId, long accountId) {
        return jdbc.sql("""
                INSERT OR IGNORE INTO notice_read (notice_id, account_id, read_at)
                SELECT id, ?, ? FROM notice WHERE tenant_id = ?
                """)
                .param(accountId).param(Timestamps.now()).param(tenantId)
                .update();
    }

    /** 这批通知里该账号已读的那些。用于给列表逐条打上已读态。 */
    public Set<Long> readIdsOf(long accountId, Collection<Long> noticeIds) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> read = new java.util.HashSet<>();
        for (List<Long> chunk : partition(List.copyOf(noticeIds))) {
            String placeholders =
                    String.join(", ", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> params = new java.util.ArrayList<>(chunk.size() + 1);
            params.add(accountId);
            params.addAll(chunk);
            read.addAll(jdbc.sql(
                    "SELECT notice_id FROM notice_read WHERE account_id = ? AND notice_id IN ("
                            + placeholders + ")")
                    .params(params).query(Long.class).list());
        }
        return read;
    }

    private static List<List<Long>> partition(List<Long> ids) {
        // 三列一行时每行占 3 个参数，按最小的那个上限切。
        int size = MAX_PARAMETERS / 3;
        if (ids.size() <= size) {
            return List.of(ids);
        }
        List<List<Long>> chunks = new java.util.ArrayList<>();
        for (int from = 0; from < ids.size(); from += size) {
            chunks.add(ids.subList(from, Math.min(from + size, ids.size())));
        }
        return chunks;
    }
}
