package io.github.jtconsole.security;

import io.github.jtconsole.config.ConsoleProperties;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class SessionTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionTokenService.class);

    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Clock clock;
    private final SecureRandom random;
    private final List<SessionRevocationListener> revocationListeners;
    private final Map<String, Session> accessTokens = new HashMap<>();
    private final Map<String, Session> refreshTokens = new HashMap<>();
    private final Map<String, Session> activeSessionsById = new HashMap<>();

    public SessionTokenService(ConsoleProperties properties) {
        this(properties, Clock.systemUTC(), new SecureRandom(), List.of());
    }

    @Autowired
    public SessionTokenService(
            ConsoleProperties properties,
            List<SessionRevocationListener> revocationListeners) {
        this(properties, Clock.systemUTC(), new SecureRandom(), revocationListeners);
    }

    SessionTokenService(ConsoleProperties properties, Clock clock, SecureRandom random) {
        this(properties, clock, random, List.of());
    }

    SessionTokenService(
            ConsoleProperties properties,
            Clock clock,
            SecureRandom random,
            List<SessionRevocationListener> revocationListeners) {
        this.accessTokenTtl = requirePositive(
                properties.getSecurity().getAccessTokenTtl(), "访问 token 有效期");
        this.refreshTokenTtl = requirePositive(
                properties.getSecurity().getRefreshTokenTtl(), "刷新 token 有效期");
        this.clock = clock;
        this.random = random;
        this.revocationListeners = List.copyOf(revocationListeners);
    }

    /**
     * 为已认证账号签发会话。会话只携带身份快照（账号、用户名、租户），
     * 权限与数据范围每次请求另行解析——访问 token 有效期是 7 天，
     * 把权限固化进会话意味着调整角色要等重新登录才生效。
     */
    public synchronized TokenPair issue(long accountId, String username, Long tenantId) {
        Instant now = clock.instant();
        purgeExpired(now);
        Session session = new Session(randomToken(), accountId, username, tenantId, now);
        activeSessionsById.put(session.sessionId, session);
        rotate(session, now);
        return session.tokenPair();
    }

    /**
     * 验证访问 token，返回其已认证用户名。该方法也是 WebSocket 握手使用的稳定契约。
     */
    public synchronized Optional<String> validateAccessToken(String accessToken) {
        return validateAccessSession(accessToken).map(AuthenticatedSession::username);
    }

    public synchronized Optional<AuthenticatedSession> validateAccessSession(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Optional.empty();
        }
        Session session = accessTokens.get(accessToken);
        if (session == null || session.revoked.get() || !accessToken.equals(session.accessToken)) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(session.accessExpiresAt)) {
            accessTokens.remove(accessToken);
            revokeAccessCredential(session);
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedSession(
                session,
                session.accessCredential,
                session.accessExpiresAt,
                clock));
    }

    public synchronized Optional<TokenPair> rotateRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        Session session = refreshTokens.get(refreshToken);
        Instant now = clock.instant();
        if (session == null
                || session.revoked.get()
                || !refreshToken.equals(session.refreshToken)
                || !now.isBefore(session.refreshExpiresAt)) {
            if (session != null) {
                revoke(session);
            }
            return Optional.empty();
        }

        accessTokens.remove(session.accessToken);
        refreshTokens.remove(session.refreshToken);
        revokeAccessCredential(session);
        rotate(session, now);
        return Optional.of(session.tokenPair());
    }

    private void purgeExpired(Instant now) {
        Iterator<Map.Entry<String, Session>> accessEntries = accessTokens.entrySet().iterator();
        while (accessEntries.hasNext()) {
            Session session = accessEntries.next().getValue();
            if (session.revoked.get()) {
                accessEntries.remove();
            } else if (!now.isBefore(session.accessExpiresAt)) {
                accessEntries.remove();
                revokeAccessCredential(session);
            }
        }
        Iterator<Map.Entry<String, Session>> refreshEntries = refreshTokens.entrySet().iterator();
        while (refreshEntries.hasNext()) {
            Session session = refreshEntries.next().getValue();
            if (session.revoked.get() || !now.isBefore(session.refreshExpiresAt)) {
                refreshEntries.remove();
                accessTokens.remove(session.accessToken);
                markRevokedAndNotify(session);
            }
        }
    }

    public synchronized boolean revokeSessionForAccessToken(String accessToken) {
        Session session = accessTokens.get(accessToken);
        if (session == null || session.revoked.get()) {
            return false;
        }
        revoke(session);
        return true;
    }

    public synchronized boolean revokeSession(AuthenticatedSession authentication) {
        if (authentication == null) {
            return false;
        }
        Session session = activeSessionsById.get(authentication.sessionId);
        if (session == null
                || session.revoked.get()
                || session.revoked != authentication.sessionRevoked) {
            return false;
        }
        revoke(session);
        return true;
    }

    /**
     * 撤销某账号的全部会话。账号被禁用、删除或被管理员重置密码时调用——
     * 访问 token 有效期已放宽到 7 天，不即时撤销等于禁用形同虚设。
     *
     * @param exceptSessionId 需要保留的会话（用户改自己密码时保留当前会话），可为空
     * @return 实际撤销的会话数
     */
    public synchronized int revokeByAccount(long accountId, String exceptSessionId) {
        List<Session> targets = activeSessionsById.values().stream()
                .filter(session -> session.accountId == accountId)
                .filter(session -> !session.sessionId.equals(exceptSessionId))
                .toList();
        targets.forEach(this::revoke);
        return targets.size();
    }

    /** 撤销某租户全部账号的会话。租户停用或到期时调用。 */
    public synchronized int revokeByTenant(long tenantId) {
        List<Session> targets = activeSessionsById.values().stream()
                .filter(session -> session.tenantId != null && session.tenantId == tenantId)
                .toList();
        targets.forEach(this::revoke);
        return targets.size();
    }

    private void rotate(Session session, Instant now) {
        session.accessToken = randomToken();
        session.accessCredential = new AccessCredential(randomToken());
        session.refreshToken = randomToken();
        session.accessExpiresAt = now.plus(accessTokenTtl);
        session.refreshExpiresAt = now.plus(refreshTokenTtl);
        accessTokens.put(session.accessToken, session);
        refreshTokens.put(session.refreshToken, session);
    }

    private void revoke(Session session) {
        if (session.revoked.get()) {
            return;
        }
        accessTokens.remove(session.accessToken);
        refreshTokens.remove(session.refreshToken);
        markRevokedAndNotify(session);
    }

    private void markRevokedAndNotify(Session session) {
        if (!session.revoked.compareAndSet(false, true)) {
            return;
        }
        activeSessionsById.remove(session.sessionId, session);
        if (session.accessCredential != null) {
            session.accessCredential.active.set(false);
        }
        for (SessionRevocationListener listener : revocationListeners) {
            try {
                listener.onSessionRevoked(session.sessionId);
            } catch (RuntimeException listenerFailure) {
                LOGGER.warn("Session revocation listener failed (listenerType={})",
                        listener.getClass().getName());
            }
        }
    }

    private void revokeAccessCredential(Session session) {
        AccessCredential credential = session.accessCredential;
        if (credential == null || !credential.active.compareAndSet(true, false)) {
            return;
        }
        for (SessionRevocationListener listener : revocationListeners) {
            try {
                listener.onAccessCredentialRevoked(credential.credentialId);
            } catch (RuntimeException listenerFailure) {
                LOGGER.warn("Access credential revocation listener failed (listenerType={})",
                        listener.getClass().getName());
            }
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + "必须为正数");
        }
        return value;
    }

    public record TokenPair(
            String token,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt) {
    }

    public static final class AuthenticatedSession implements Principal {
        private final String sessionId;
        private final long accountId;
        private final String username;
        private final Long tenantId;
        private final AtomicBoolean sessionRevoked;
        private final AccessCredential accessCredential;
        private final Instant accessTokenExpiresAt;
        private final Clock clock;

        private AuthenticatedSession(
                Session session,
                AccessCredential accessCredential,
                Instant accessTokenExpiresAt,
                Clock clock) {
            this.sessionId = session.sessionId;
            this.accountId = session.accountId;
            this.username = session.username;
            this.tenantId = session.tenantId;
            this.sessionRevoked = session.revoked;
            this.accessCredential = accessCredential;
            this.accessTokenExpiresAt = accessTokenExpiresAt;
            this.clock = clock;
        }

        public String sessionId() {
            return sessionId;
        }

        public String accessCredentialId() {
            return accessCredential.credentialId;
        }

        public long accountId() {
            return accountId;
        }

        /** 会话所属租户；平台账号为空。 */
        public Long tenantId() {
            return tenantId;
        }

        public String username() {
            return username;
        }

        @Override
        public String getName() {
            return username;
        }

        public AuthenticationState state() {
            if (sessionRevoked.get()) {
                return AuthenticationState.SESSION_REVOKED;
            }
            if (!accessCredential.active.get() || !clock.instant().isBefore(accessTokenExpiresAt)) {
                return AuthenticationState.ACCESS_CREDENTIAL_REVOKED;
            }
            return AuthenticationState.ACTIVE;
        }
    }

    public enum AuthenticationState {
        ACTIVE,
        ACCESS_CREDENTIAL_REVOKED,
        SESSION_REVOKED
    }

    private static final class AccessCredential {
        private final String credentialId;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private AccessCredential(String credentialId) {
            this.credentialId = credentialId;
        }
    }

    private static final class Session {
        private final String sessionId;
        private final long accountId;
        private final String username;
        private final Long tenantId;
        private final Instant issuedAt;
        private String accessToken;
        private AccessCredential accessCredential;
        private String refreshToken;
        private Instant accessExpiresAt;
        private Instant refreshExpiresAt;
        private final AtomicBoolean revoked = new AtomicBoolean();

        private Session(String sessionId, long accountId, String username, Long tenantId,
                Instant issuedAt) {
            this.sessionId = sessionId;
            this.accountId = accountId;
            this.username = username;
            this.tenantId = tenantId;
            this.issuedAt = issuedAt;
        }

        private TokenPair tokenPair() {
            return new TokenPair(
                    accessToken, refreshToken, accessExpiresAt, refreshExpiresAt);
        }
    }
}
