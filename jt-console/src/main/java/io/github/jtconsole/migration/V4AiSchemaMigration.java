package io.github.jtconsole.migration;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v4：AI 助手与运营简报的表结构。
 *
 * <p>四张新表加套餐上的一个新列，全部是新增，没有数据改写——回滚只需回退代码，旧版本不认识这些表
 * 也不会去读它们。
 *
 * <p>{@code plan.max_ai_calls_monthly} 沿用既有配额的「0 = 不限量」约定，默认 0 使升级后所有租户
 * 的 AI 使用不因升级而受限。但它与车辆数、账号数上限有一处本质不同：那两者是**存量**上限，靠
 * {@code COUNT(*)} 现算即可；AI 调用是**流量**上限，「本月用了多少次」没有任何现成载体，所以必须
 * 有 {@code ai_usage} 这张计量表。
 */
@Component
public class V4AiSchemaMigration implements SchemaMigration {

    @Override
    public int version() {
        return 4;
    }

    @Override
    public String description() {
        return "创建 AI 用量、对话留痕与运营简报表，套餐补充月度 AI 调用上限";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        addColumnIfMissing(jdbc, "plan", "max_ai_calls_monthly", "INTEGER NOT NULL DEFAULT 0");

        // 计量表。同步写入，不走审计那种「队列满即丢」的异步通道：审计丢一条只是少一条追溯记录，
        // 计量丢一条就是少收一次钱。
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS ai_usage (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id         INTEGER,
                    account_id        INTEGER NOT NULL,
                    kind              TEXT NOT NULL,
                    model             TEXT NOT NULL,
                    rounds            INTEGER NOT NULL DEFAULT 0,
                    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
                    completion_tokens INTEGER NOT NULL DEFAULT 0,
                    month             TEXT NOT NULL,
                    created_at        TEXT NOT NULL
                )
                """);
        // 配额判定恒为「某租户某月的 chat 次数」，三列的联合索引正好是它的完整前缀。
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_ai_usage_tenant_month
                    ON ai_usage (tenant_id, month, kind)
                """);

        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS ai_conversation (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id  INTEGER,
                    account_id INTEGER NOT NULL,
                    title      TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_ai_conversation_account
                    ON ai_conversation (account_id, updated_at DESC)
                """);

        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS ai_message (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id   INTEGER NOT NULL,
                    role              TEXT NOT NULL,
                    content           TEXT NOT NULL,
                    tool_trace        TEXT,
                    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
                    completion_tokens INTEGER NOT NULL DEFAULT 0,
                    created_at        TEXT NOT NULL
                )
                """);
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_ai_message_conversation
                    ON ai_message (conversation_id, id)
                """);

        // 唯一约束就是幂等的实现方式：重复生成走 upsert 覆盖，而不是新增一份。
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS ai_report (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id         INTEGER NOT NULL,
                    report_date       TEXT NOT NULL,
                    status            TEXT NOT NULL,
                    content_md        TEXT NOT NULL DEFAULT '',
                    error             TEXT,
                    model             TEXT NOT NULL DEFAULT '',
                    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
                    completion_tokens INTEGER NOT NULL DEFAULT 0,
                    created_at        TEXT NOT NULL,
                    updated_at        TEXT NOT NULL,
                    UNIQUE (tenant_id, report_date)
                )
                """);
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_ai_report_tenant_date
                    ON ai_report (tenant_id, report_date DESC)
                """);
    }

    /**
     * SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}；版本号已保证迁移只跑一次，
     * 这里再查一次列清单是为了容忍手工改过库的环境。
     */
    private static void addColumnIfMissing(JdbcClient jdbc, String table, String column, String type) {
        List<String> existing = jdbc.sql("SELECT name FROM pragma_table_info(?)")
                .param(table)
                .query(String.class)
                .list();
        if (existing.stream().anyMatch(name -> name.equalsIgnoreCase(column))) {
            return;
        }
        // 表名与列名来自本类常量，不含外部输入。
        execute(jdbc, "ALTER TABLE %s ADD COLUMN %s %s"
                .formatted(table, column, type.toUpperCase(Locale.ROOT)));
    }

    private static void execute(JdbcClient jdbc, String sql) {
        jdbc.sql(sql).update();
    }
}
