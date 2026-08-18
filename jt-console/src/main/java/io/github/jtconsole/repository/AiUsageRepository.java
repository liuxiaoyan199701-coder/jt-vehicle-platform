package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * AI 用量计量。
 *
 * <p>**同步写**，不走审计那种「队列满即丢」的异步通道：审计允许丢是因为它的定位是运营追溯，
 * 而这张表是计费依据，丢一条就是少收一次钱。写入量也不构成压力——一次对话一行。
 */
@Repository
public class AiUsageRepository {

    /** 对话消耗，计入租户配额。 */
    public static final String KIND_CHAT = "chat";
    /** 简报消耗，计量但不占租户配额——平台调度触发的消耗不该扣用户额度。 */
    public static final String KIND_REPORT = "report";

    private final JdbcClient jdbc;

    public AiUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(
            Long tenantId, long accountId, String kind, String model,
            int rounds, int promptTokens, int completionTokens, String month) {
        jdbc.sql("""
                        INSERT INTO ai_usage (tenant_id, account_id, kind, model, rounds,
                                              prompt_tokens, completion_tokens, month, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(tenantId).param(accountId).param(kind).param(model).param(rounds)
                .param(promptTokens).param(completionTokens).param(month)
                .param(Timestamps.now())
                .update();
    }

    /** 某租户某自然月的对话次数。简报消耗刻意不计入。 */
    public long countChatCalls(Long tenantId, String month) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*) FROM ai_usage
                        WHERE tenant_id IS ? AND month = ? AND kind = ?
                        """)
                .param(tenantId).param(month).param(KIND_CHAT)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }
}
