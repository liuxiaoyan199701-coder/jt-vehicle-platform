package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 看板要点的持久化。
 *
 * <p>表在 v4 迁移里就建好了（原本为「每日运营简报」预留），至今零代码使用。
 * 这里第一次用起来：{@code UNIQUE (tenant_id, report_date)} 正好一天一行，
 * 定时任务每小时**覆盖**同一行，{@code updated_at} 即新鲜度。
 *
 * <p><b>为什么一天一行而不是一小时一行</b>：看板要的是「此刻值得管什么」，不是流水账。
 * 保留历史每小时的版本没有消费者，却会让这张表按租户数×24 的速度膨胀。
 */
@Repository
public class AiReportRepository {

    private final JdbcClient jdbc;

    public AiReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入或覆盖某租户某天的要点。
     *
     * <p>失败也要落库（{@code status='FAILED'} 加 {@code error}）：一块空白的看板分不清是
     * 「今天没事」还是「生成挂了」，而这两者的处理方式完全相反。
     */
    public void upsert(
            long tenantId,
            String reportDate,
            String status,
            String contentJson,
            String error,
            String model,
            int promptTokens,
            int completionTokens,
            long generatedMs) {
        String now = Timestamps.now();
        jdbc.sql("""
                        INSERT INTO ai_report
                            (tenant_id, report_date, status, content_md, content_json, error,
                             model, prompt_tokens, completion_tokens, generated_ms,
                             created_at, updated_at)
                        VALUES (?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, report_date) DO UPDATE SET
                            status = excluded.status,
                            content_json = excluded.content_json,
                            error = excluded.error,
                            model = excluded.model,
                            prompt_tokens = excluded.prompt_tokens,
                            completion_tokens = excluded.completion_tokens,
                            generated_ms = excluded.generated_ms,
                            updated_at = excluded.updated_at
                        """)
                .param(tenantId)
                .param(reportDate)
                .param(status)
                .param(contentJson)
                .param(error)
                .param(model)
                .param(promptTokens)
                .param(completionTokens)
                .param(generatedMs)
                .param(now)
                .param(now)
                .update();
    }

    /** 读取某租户某天的要点。 */
    public Optional<Row> find(long tenantId, String reportDate) {
        return jdbc.sql("""
                        SELECT tenant_id AS tenantId, report_date AS reportDate, status,
                               content_json AS contentJson, error, model,
                               generated_ms AS generatedMs, updated_at AS updatedAt
                        FROM ai_report
                        WHERE tenant_id = ? AND report_date = ?
                        """)
                .param(tenantId)
                .param(reportDate)
                .query(Row.class)
                .optional();
    }

    /** 清理过期要点。看板只看当天，历史留一段时间供排查即可。 */
    public int deleteOlderThan(String cutoffDate) {
        return jdbc.sql("DELETE FROM ai_report WHERE report_date < ?")
                .param(cutoffDate)
                .update();
    }

    public record Row(
            long tenantId,
            String reportDate,
            String status,
            String contentJson,
            String error,
            String model,
            Long generatedMs,
            String updatedAt) {

        public boolean succeeded() {
            return "OK".equals(status);
        }

        public Map<String, Object> asMeta() {
            return Map.of(
                    "status", status,
                    "updatedAt", updatedAt == null ? "" : updatedAt,
                    "model", model == null ? "" : model);
        }
    }
}
