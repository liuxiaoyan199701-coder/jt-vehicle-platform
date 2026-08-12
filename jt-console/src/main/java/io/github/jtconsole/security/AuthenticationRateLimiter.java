package io.github.jtconsole.security;

import io.github.jtconsole.config.ConsoleProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class AuthenticationRateLimiter {

    private final int maxFailures;
    private final Duration window;
    private final Duration blockDuration;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, AttemptState> attempts =
            new LinkedHashMap<>(16, 0.75F, true);

    @Autowired
    public AuthenticationRateLimiter(ConsoleProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AuthenticationRateLimiter(ConsoleProperties properties, Clock clock) {
        ConsoleProperties.RateLimit config = properties.getSecurity().getRateLimit();
        maxFailures = requirePositive(config.getMaxFailures(), "认证最大失败次数");
        maxEntries = requirePositive(config.getMaxEntries(), "认证限流状态容量");
        window = requirePositive(config.getWindow(), "认证失败窗口");
        blockDuration = requirePositive(config.getBlockDuration(), "认证阻断时长");
        this.clock = clock;
    }

    public synchronized boolean isLoginBlocked(String sourceAddress, String username) {
        return isAnyBlocked(loginKeys(sourceAddress, username));
    }

    public synchronized void recordLoginFailure(String sourceAddress, String username) {
        recordFailure(loginKeys(sourceAddress, username));
    }

    public synchronized void recordLoginSuccess(String sourceAddress, String username) {
        clear(loginKeys(sourceAddress, username));
    }

    public synchronized boolean isRefreshBlocked(String sourceAddress, String refreshToken) {
        return isAnyBlocked(refreshKeys(sourceAddress, refreshToken));
    }

    public synchronized void recordRefreshFailure(String sourceAddress, String refreshToken) {
        recordFailure(refreshKeys(sourceAddress, refreshToken));
    }

    public synchronized void recordRefreshSuccess(String sourceAddress, String refreshToken) {
        clear(refreshKeys(sourceAddress, refreshToken));
    }

    private boolean isAnyBlocked(List<String> keys) {
        Instant now = clock.instant();
        for (String key : keys) {
            AttemptState state = attempts.get(key);
            if (state != null && state.isBlocked(now)) {
                return true;
            }
            if (state != null && state.isStale(now, window)) {
                attempts.remove(key);
            }
        }
        return false;
    }

    private void recordFailure(List<String> keys) {
        Instant now = clock.instant();
        for (String key : keys) {
            AttemptState state = attempts.get(key);
            if (state == null || state.isStale(now, window)) {
                ensureCapacity();
                state = new AttemptState(now);
                attempts.put(key, state);
            }
            state.failures++;
            if (state.failures >= maxFailures) {
                state.blockedUntil = now.plus(blockDuration);
            }
        }
    }

    private void clear(List<String> keys) {
        keys.forEach(attempts::remove);
    }

    private void ensureCapacity() {
        while (attempts.size() >= maxEntries) {
            String eldest = attempts.keySet().iterator().next();
            attempts.remove(eldest);
        }
    }

    private static List<String> loginKeys(String sourceAddress, String username) {
        return List.of(
                "login:source:" + digest(normalize(sourceAddress)),
                "login:user:" + digest(normalize(username).toLowerCase(Locale.ROOT)));
    }

    private static List<String> refreshKeys(String sourceAddress, String refreshToken) {
        return List.of(
                "refresh:source:" + digest(normalize(sourceAddress)),
                "refresh:token:" + digest(normalize(refreshToken)));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException(name + "必须为正数");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + "必须为正数");
        }
        return value;
    }

    private static final class AttemptState {
        private final Instant firstFailure;
        private int failures;
        private Instant blockedUntil;

        private AttemptState(Instant firstFailure) {
            this.firstFailure = firstFailure;
        }

        private boolean isBlocked(Instant now) {
            return blockedUntil != null && now.isBefore(blockedUntil);
        }

        private boolean isStale(Instant now, Duration window) {
            if (blockedUntil != null) {
                return !now.isBefore(blockedUntil);
            }
            return !now.isBefore(firstFailure.plus(window));
        }
    }
}
