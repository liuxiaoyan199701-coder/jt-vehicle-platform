package io.github.jtconsole.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

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
        String now = Instant.now().toString();
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
        String now = Instant.now().toString();
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
                .param(java.time.Instant.now().toString()).param(conversationId).param(accountId)
                .update();
        return removed;
    }

    /** 分批清理超期会话。连同其消息一起删，不留孤儿行。 */
    public int deleteOlderThan(String cutoff, int batchSize) {
        jdbc.sql("""
                        DELETE FROM ai_message WHERE conversation_id IN (
                            SELECT id FROM ai_conversation WHERE updated_at < ? LIMIT ?)
                        """)
                .param(cutoff).param(batchSize).update();
        return jdbc.sql("""
                        DELETE FROM ai_conversation WHERE id IN (
                            SELECT id FROM ai_conversation WHERE updated_at < ? LIMIT ?)
                        """)
                .param(cutoff).param(batchSize)
                .update();
    }
}
