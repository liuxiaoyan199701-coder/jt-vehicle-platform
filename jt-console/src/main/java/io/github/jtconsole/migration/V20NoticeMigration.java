package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v20：主动通知。把够格的看板发现固化成一条通知，让它在无人打开页面时也送得到人。
 *
 * <p><b>为什么落库而不是只推送</b>：主动通知最有价值的场景恰恰是人没在看屏幕的时候。
 * 只推不存，那一刻产生的通知就永久丢失了；落库还顺带解决已读状态跨设备、跨会话保持。
 *
 * <p><b>已读单独一张表</b>。同一条通知对一个租户里的多个人各自有已读状态，
 * 记在 {@code notice} 上就变成「谁先看了算所有人都看了」——那会让第二个人永远看不到它。
 *
 * <p><b>唯一约束带 {@code created_at} 而不是只 {@code (tenant_id, dedup_key)}</b>：
 * 同一件事在静默窗口过去之后可以再次通知，那是新的一条记录而不是更新旧的，
 * 「上个月是不是提醒过我」要答得上来。抑制判定查「该键最近一条」，不靠唯一约束实现——
 * 这条约束只挡住同一毫秒内的重复写入。
 */
@Component
public class V20NoticeMigration implements SchemaMigration {

    @Override
    public int version() {
        return 20;
    }

    @Override
    public String description() {
        return "建 notice 主动通知表与 notice_read 已读表";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS notice (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id  INTEGER NOT NULL,
                    dedup_key  TEXT NOT NULL,
                    category   TEXT NOT NULL,
                    severity   TEXT NOT NULL,
                    summary    TEXT NOT NULL,
                    facts      TEXT,
                    device_ids TEXT,
                    link_route TEXT,
                    link_query TEXT,
                    link_label TEXT,
                    created_at TEXT NOT NULL
                )
                """).update();
        // 清单与未读计数都按「本租户、最近优先」翻页，这是唯一的读取形态。
        jdbc.sql("""
                CREATE INDEX IF NOT EXISTS idx_notice_tenant_time
                ON notice (tenant_id, created_at DESC)
                """).update();
        // 抑制判定查「该键最近一条」，走这条索引而不是全表扫。
        jdbc.sql("""
                CREATE INDEX IF NOT EXISTS idx_notice_dedup
                ON notice (tenant_id, dedup_key, created_at DESC)
                """).update();
        jdbc.sql("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_notice_dedup_time
                ON notice (tenant_id, dedup_key, created_at)
                """).update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS notice_read (
                    notice_id  INTEGER NOT NULL,
                    account_id INTEGER NOT NULL,
                    read_at    TEXT NOT NULL,
                    PRIMARY KEY (notice_id, account_id)
                )
                """).update();
    }
}
