package io.github.jtconsole.repository;

import io.github.jtconsole.security.SessionStore;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 会话的 SQLite 落库实现。 */
@Repository
public class JdbcSessionStore implements SessionStore {

    private final JdbcClient jdbc;

    public JdbcSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Record session) {
        // 令牌轮换会用新摘要覆盖同一条会话，因此是 upsert 而不是 insert。
        jdbc.sql("""
                        INSERT INTO user_session (session_id, account_id, username, tenant_id,
                                                  access_token_hash, refresh_token_hash,
                                                  issued_at, access_expires_at, refresh_expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(session_id) DO UPDATE SET
                            access_token_hash = excluded.access_token_hash,
                            refresh_token_hash = excluded.refresh_token_hash,
                            access_expires_at = excluded.access_expires_at,
                            refresh_expires_at = excluded.refresh_expires_at
                        """)
                .param(session.sessionId()).param(session.accountId()).param(session.username())
                .param(session.tenantId()).param(session.accessTokenHash())
                .param(session.refreshTokenHash()).param(session.issuedAt().toString())
                .param(session.accessExpiresAt().toString())
                .param(session.refreshExpiresAt().toString())
                .update();
    }

    @Override
    public void delete(String sessionId) {
        jdbc.sql("DELETE FROM user_session WHERE session_id = ?").param(sessionId).update();
    }

    @Override
    public List<Record> loadLive(Instant now) {
        // 以刷新令牌的有效期为准：访问令牌过期但刷新令牌还在的会话仍然有效，
        // 前端会用它换一张新的访问令牌，此时不该要求重新登录。
        String cutoff = now.toString();
        jdbc.sql("DELETE FROM user_session WHERE refresh_expires_at <= ?").param(cutoff).update();
        return jdbc.sql("""
                        SELECT session_id, account_id, username, tenant_id,
                               access_token_hash, refresh_token_hash,
                               issued_at, access_expires_at, refresh_expires_at
                        FROM user_session
                        """)
                .query((rs, rowNum) -> new Record(
                        rs.getString("session_id"),
                        rs.getLong("account_id"),
                        rs.getString("username"),
                        RowValues.nullableLong(rs, "tenant_id"),
                        rs.getString("access_token_hash"),
                        rs.getString("refresh_token_hash"),
                        Instant.parse(rs.getString("issued_at")),
                        Instant.parse(rs.getString("access_expires_at")),
                        Instant.parse(rs.getString("refresh_expires_at"))))
                .list();
    }
}
