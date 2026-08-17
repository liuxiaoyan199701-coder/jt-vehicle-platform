package io.github.jtconsole.security;

import java.time.Instant;
import java.util.List;

/**
 * 会话的持久化出口。
 *
 * <p>做成接口是为了让 {@link SessionTokenService} 的单元测试不必起数据库——它们关心的是令牌轮换
 * 与过期判定，与「存到哪」无关。生产环境注入 JDBC 实现，测试用 {@link #inMemory()}。
 *
 * <p>只流转令牌的摘要，不流转令牌原文：原文是即用型凭据，落库等于把可直接冒充的钥匙写进磁盘。
 */
public interface SessionStore {

    void save(Record session);

    void delete(String sessionId);

    /** 加载尚未过期的会话。启动时调用一次，用于重建内存索引。 */
    List<Record> loadLive(Instant now);

    /**
     * @param accessTokenHash  访问令牌的 SHA-256 摘要
     * @param refreshTokenHash 刷新令牌的 SHA-256 摘要
     */
    record Record(
            String sessionId,
            long accountId,
            String username,
            Long tenantId,
            String accessTokenHash,
            String refreshTokenHash,
            Instant issuedAt,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {
    }

    /** 不持久化的实现。会话仍然只活在内存里，重启即失效——测试与嵌入式场景够用。 */
    static SessionStore inMemory() {
        return new SessionStore() {
            @Override
            public void save(Record session) {
                // 不落盘
            }

            @Override
            public void delete(String sessionId) {
                // 不落盘
            }

            @Override
            public List<Record> loadLive(Instant now) {
                return List.of();
            }
        };
    }
}
