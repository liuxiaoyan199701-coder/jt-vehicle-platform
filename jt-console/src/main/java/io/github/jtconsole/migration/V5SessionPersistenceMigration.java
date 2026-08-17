package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v5：把登录会话落库，使其能跨服务重启存活。
 *
 * <p>此前会话只存在内存的 HashMap 里，服务一重启全部作废——每次发版所有人都得重新登录。
 * 访问令牌本来就配了 7 天有效期，却因为一次几秒的重启而失效，这与配置的意图完全相悖。
 *
 * <p>表里存的是令牌的 SHA-256 摘要而不是令牌本身。会话令牌是即用型凭据，拿到原文就能直接冒充
 * 使用者，比密码哈希危险得多；存摘要则即便库被读走也无法反推出可用的令牌。校验时对传入的令牌
 * 做同样的摘要再比对，行为不变。
 */
@Component
public class V5SessionPersistenceMigration implements SchemaMigration {

    @Override
    public int version() {
        return 5;
    }

    @Override
    public String description() {
        return "创建会话表，使登录状态跨重启存活";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS user_session (
                            session_id         TEXT PRIMARY KEY,
                            account_id         INTEGER NOT NULL,
                            username           TEXT NOT NULL,
                            tenant_id          INTEGER,
                            access_token_hash  TEXT NOT NULL,
                            refresh_token_hash TEXT NOT NULL,
                            issued_at          TEXT NOT NULL,
                            access_expires_at  TEXT NOT NULL,
                            refresh_expires_at TEXT NOT NULL
                        )
                        """)
                .update();
        // 校验走摘要查找，是最热的一条路径。
        jdbc.sql("""
                        CREATE UNIQUE INDEX IF NOT EXISTS ux_session_access
                            ON user_session (access_token_hash)
                        """)
                .update();
        jdbc.sql("""
                        CREATE UNIQUE INDEX IF NOT EXISTS ux_session_refresh
                            ON user_session (refresh_token_hash)
                        """)
                .update();
        jdbc.sql("""
                        CREATE INDEX IF NOT EXISTS idx_session_account
                            ON user_session (account_id)
                        """)
                .update();
    }
}
