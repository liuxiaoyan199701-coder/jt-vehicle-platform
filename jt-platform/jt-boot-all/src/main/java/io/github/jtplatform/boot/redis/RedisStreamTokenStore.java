package io.github.jtplatform.boot.redis;

import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.auth.TokenValidationResult;
import io.github.jtplatform.common.model.StreamKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public final class RedisStreamTokenStore implements StreamTokenStore {
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final RedisRegistrySupport support;
    private final SecureRandom random;
    private final Clock clock;
    private final RedisScript<String> consumeScript;

    public RedisStreamTokenStore(
            StringRedisTemplate redis,
            RedisRegistrySupport support,
            SecureRandom random,
            Clock clock) {
        this.redis = redis;
        this.support = support;
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.consumeScript = support.stringScript("/redis/token-consume.lua");
    }

    @Override
    public String issue(StreamKey streamKey, String mediaInstanceId, Duration timeToLive) {
        Objects.requireNonNull(streamKey, "streamKey");
        String instanceId = requireText(mediaInstanceId, "mediaInstanceId");
        if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
        String token = randomToken();
        String payload = support.write(new TokenPayload(
                streamKey.externalId(), instanceId, String.valueOf(clock.millis() + timeToLive.toMillis()), false));
        redis.opsForValue().set(support.tokenKey(token), payload, timeToLive);
        redis.opsForSet().add(support.tokenIndexKey(), token);
        return token;
    }

    @Override
    public TokenValidationResult validateAndConsume(String token, StreamKey streamKey, String mediaInstanceId) {
        if (token == null || token.isBlank()) {
            return TokenValidationResult.MISSING;
        }
        Objects.requireNonNull(streamKey, "streamKey");
        String instanceId = requireText(mediaInstanceId, "mediaInstanceId");
        String result = redis.execute(consumeScript,
                List.of(support.tokenKey(token), support.tokenIndexKey()),
                streamKey.externalId(), instanceId, String.valueOf(clock.millis()), token);
        return result == null ? TokenValidationResult.MISSING : TokenValidationResult.valueOf(result);
    }

    @Override
    public int purgeExpired() {
        Set<String> tokens = redis.opsForSet().members(support.tokenIndexKey());
        if (tokens == null) {
            return 0;
        }
        long now = clock.millis();
        int purged = 0;
        for (String token : tokens) {
            String json = redis.opsForValue().get(support.tokenKey(token));
            if (json == null) {
                redis.opsForSet().remove(support.tokenIndexKey(), token);
                continue;
            }
            TokenPayload payload = support.read(json, TokenPayload.class);
            if (Long.parseLong(payload.expiresAtMillis()) <= now) {
                redis.delete(support.tokenKey(token));
                redis.opsForSet().remove(support.tokenIndexKey(), token);
                purged++;
            }
        }
        return purged;
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (Boolean.TRUE.equals(redis.hasKey(support.tokenKey(token))));
        return token;
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }

    private record TokenPayload(
            String streamKey,
            String mediaInstanceId,
            String expiresAtMillis,
            boolean consumed) {
    }
}
