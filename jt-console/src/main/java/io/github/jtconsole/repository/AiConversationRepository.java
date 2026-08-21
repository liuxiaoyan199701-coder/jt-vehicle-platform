package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话留痕。
 *
 * <p>两个用途合一：一是事后追溯「谁问过什么、助手查了哪些数据」，二是让用户切页面或刷新之后
 * 还能接着上次聊——后者是产品需求，前者是审计需求，同一份数据都能满足。
 *
 * <p>按账号隔离读取：会话是私人的，同租户的另一个人也不该看到你问过什么。
 */
@Repository
public class AiConversationRepository {

    private final JdbcClient jdbc;

    public AiConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long create(Long tenantId, long accountId, String title) {
        String now = Timestamps.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO ai_conversation (tenant_id, account_id, title, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .param(tenantId).param(accountId).param(title).param(now).param(now)
                .update(key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("创建 AI 会话后未返回主键");
        }
        return value.longValue();
    }

    /** 会话必须属于该账号才算存在——避免带一个别人的 id 就能读到别人的对话。 */
    public boolean belongsTo(long conversationId, long accountId) {
        Integer count = jdbc.sql(
                        "SELECT COUNT(*) FROM ai_conversation WHERE id = ? AND account_id = ?")
                .param(conversationId).param(accountId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public void appendMessage(
            long conversationId, String role, String content, String toolTrace,
            int promptTokens, int completionTokens) {
        String now = Timestamps.now();
        jdbc.sql("""
                        INSERT INTO ai_message (conversation_id, role, content, tool_trace,
                                                prompt_tokens, completion_tokens, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(conversationId).param(role).param(content).param(toolTrace)
                .param(promptTokens).param(completionTokens).param(now)
                .update();
        jdbc.sql("UPDATE ai_conversation SET updated_at = ? WHERE id = ?")
                .param(now).param(conversationId)
                .update();
    }

    /** 某账号最近的会话，最新在前。 */
    public List<Map<String, Object>> findRecent(long accountId, int limit) {
        return jdbc.sql("""
                        SELECT id, title, created_at AS createdAt, updated_at AS updatedAt
                        FROM ai_conversation
                        WHERE account_id = ?
                        ORDER BY updated_at DESC, id DESC
                        LIMIT ?
                        """)
                .param(accountId).param(limit)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> findMessages(long conversationId, int limit) {
        return jdbc.sql("""
                        SELECT role, content, tool_trace AS toolTrace, created_at AS createdAt
                        FROM ai_message
                        WHERE conversation_id = ?
                        ORDER BY id
                        LIMIT ?
                        """)
                .param(conversationId).param(limit)
                .query()
                .listOfRows();
    }

    public int delete(long conversationId, long accountId) {
        jdbc.sql("""
                        DELETE FROM ai_message WHERE conversation_id IN (
                            SELECT id FROM ai_conversation WHERE id = ? AND account_id = ?)
                        """)
                .param(conversationId).param(accountId).update();
        return jdbc.sql("DELETE FROM ai_conversation WHERE id = ? AND account_id = ?")
                .param(conversationId).param(accountId)
                .update();
    }

    /**
     * 清空某个会话的消息，会话本身保留。
     *
     * <p>与删除会话是两件事：清空是「这段聊歪了，从头再来」，会话还在列表里；
     * 删除是「这段不要了」。都限定 account_id，防止带别人的 id 来清。
     */
    public int clearMessages(long conversationId, long accountId) {
        int removed = jdbc.sql("""
                        DELETE FROM ai_message WHERE conversation_id IN (
                            SELECT id FROM ai_conversation WHERE id = ? AND account_id = ?)
                        """)
                .param(conversationId).param(accountId)
                .update();
        jdbc.sql("UPDATE ai_conversation SET updated_at = ? WHERE id = ? AND account_id = ?")
                .param(Timestamps.now()).param(conversationId).param(accountId)
                .update();
        return removed;
    }

    /**
     * 分批清理超期会话。单批在同一事务内先删消息再删会话，不留本批产生的孤儿消息。
     *
     * <p>子查询显式按 {@code updated_at, id} 选最旧的一批，避免 SQLite 在没有排序时任意选行，
     * 让积压清理可预测。批次上限由调用方配置，避免一条长事务长期占用 SQLite 唯一写锁。
     */
    @Transactional
    public int deleteOlderThan(String cutoff, int batchSize) {
        if (cutoff == null || cutoff.isBlank()) {
            throw new IllegalArgumentException("cutoff 不能为空");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须大于 0");
        }
        jdbc.sql("""
                        DELETE FROM ai_message WHERE conversation_id IN (
                            SELECT id FROM ai_conversation
                            WHERE updated_at < ?
                            ORDER BY updated_at, id
                            LIMIT ?)
                        """)
                .param(cutoff).param(batchSize).update();
        return jdbc.sql("""
                        DELETE FROM ai_conversation WHERE id IN (
                            SELECT id FROM ai_conversation
                            WHERE updated_at < ?
                            ORDER BY updated_at, id
                            LIMIT ?)
                        """)
                .param(cutoff).param(batchSize)
                .update();
    }

    /** 清理由历史异常或人工操作遗留的孤儿消息，同样限制单批条数。 */
    public int deleteOrphanMessages(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须大于 0");
        }
        return jdbc.sql("""
                        DELETE FROM ai_message WHERE id IN (
                            SELECT m.id
                            FROM ai_message m
                            LEFT JOIN ai_conversation c ON c.id = m.conversation_id
                            WHERE c.id IS NULL
                            ORDER BY m.id
                            LIMIT ?)
                        """)
                .param(batchSize)
                .update();
    }
}
