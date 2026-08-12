package io.github.jtplatform.common.auth;

import io.github.jtplatform.common.model.StreamKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryStreamTokenStore implements StreamTokenStore {
    private static final int TOKEN_BYTES = 32;

    private final ConcurrentHashMap<String, TokenGrant> grants = new ConcurrentHashMap<>();
    private final SecureRandom random;
    private final Clock clock;

    public InMemoryStreamTokenStore() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    public InMemoryStreamTokenStore(SecureRandom random, Clock clock) {
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String issue(StreamKey streamKey, String mediaInstanceId, Duration timeToLive) {
        Objects.requireNonNull(streamKey, "streamKey");
        String instanceId = requireText(mediaInstanceId, "mediaInstanceId");
        Objects.requireNonNull(timeToLive, "timeToLive");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (grants.putIfAbsent(token,
                new TokenGrant(streamKey, instanceId, clock.instant().plus(timeToLive))) != null);
        return token;
    }

    @Override
    public TokenValidationResult validateAndConsume(String token, StreamKey streamKey, String mediaInstanceId) {
        if (token == null || token.isBlank()) {
            return TokenValidationResult.MISSING;
        }
        Objects.requireNonNull(streamKey, "streamKey");
        String instanceId = requireText(mediaInstanceId, "mediaInstanceId");
        TokenGrant grant = grants.get(token);
        if (grant == null) {
            return TokenValidationResult.MISSING;
        }
        if (!clock.instant().isBefore(grant.expiresAt())) {
            grants.remove(token, grant);
            return TokenValidationResult.EXPIRED;
        }
        if (!grant.streamKey().equals(streamKey)) {
            return TokenValidationResult.WRONG_STREAM;
        }
        if (!grant.mediaInstanceId().equals(instanceId)) {
            return TokenValidationResult.WRONG_INSTANCE;
        }
        return grant.consumed().compareAndSet(false, true)
                ? TokenValidationResult.VALID
                : TokenValidationResult.REPLAYED;
    }

    @Override
    public int purgeExpired() {
        Instant now = clock.instant();
        int before = grants.size();
        grants.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        return before - grants.size();
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }

    private record TokenGrant(
            StreamKey streamKey,
            String mediaInstanceId,
            Instant expiresAt,
            AtomicBoolean consumed) {
        private TokenGrant(StreamKey streamKey, String mediaInstanceId, Instant expiresAt) {
            this(streamKey, mediaInstanceId, expiresAt, new AtomicBoolean());
        }
    }
}
