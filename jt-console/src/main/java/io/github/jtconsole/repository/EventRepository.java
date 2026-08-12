package io.github.jtconsole.repository;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 投递幂等去重。网关在 {@code AsyncMessageChannel} 中明确记录了事件可能被重复投递
 * （溢出文件在投递成功后仍可能保留并再次发送），因此必须按 eventId 判重。
 */
@Repository
public class EventRepository {

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试登记一个事件。
     *
     * <p>用 SQLite 的 {@code INSERT OR IGNORE} 而不是捕获 {@code DuplicateKeyException}：
     * SQLite 不提供标准 SQLState，主键冲突会被 Spring 翻译成未分类的
     * {@code UncategorizedSQLException}，捕获特定异常类型并不可靠。让冲突时影响行数为 0，
     * 判重就退化成一次普通的行数判断。
     *
     * @return true 表示首次出现，应当继续处理；false 表示重复投递，调用方直接跳过
     */
    public boolean markProcessed(String eventId) {
        int rows = jdbc.sql("INSERT OR IGNORE INTO processed_event (event_id, created_at) VALUES (?, ?)")
                .param(eventId)
                .param(Instant.now().toString())
                .update();
        return rows > 0;
    }

    /**
     * 清理过期的去重记录，避免表无限增长。
     */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM processed_event WHERE created_at < ?")
                .param(cutoff.toString())
                .update();
    }
}
