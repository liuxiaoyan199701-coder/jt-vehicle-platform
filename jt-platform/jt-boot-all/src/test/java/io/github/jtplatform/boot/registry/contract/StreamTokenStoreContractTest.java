package io.github.jtplatform.boot.registry.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.auth.TokenValidationResult;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

public abstract class StreamTokenStoreContractTest {
    private static final StreamKey STREAM = new StreamKey("device-1", 1, StreamKind.MAIN);

    protected final MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));

    private StreamTokenStore store;

    protected abstract StreamTokenStore newStore(Clock clock);

    protected final StreamTokenStore store() {
        if (store == null) {
            store = newStore(clock);
        }
        return store;
    }

    @Test
    void tokenIsBoundToStreamAndInstanceAndSingleUse() {
        String token = store().issue(STREAM, "media-1", Duration.ofSeconds(60));

        assertEquals(TokenValidationResult.WRONG_STREAM, store().validateAndConsume(token,
                new StreamKey("device-1", 2, StreamKind.MAIN), "media-1"));
        assertEquals(TokenValidationResult.WRONG_INSTANCE,
                store().validateAndConsume(token, STREAM, "media-2"));
        assertEquals(TokenValidationResult.VALID, store().validateAndConsume(token, STREAM, "media-1"));
        assertEquals(TokenValidationResult.REPLAYED, store().validateAndConsume(token, STREAM, "media-1"));
    }

    @Test
    void expiredTokenIsRejected() {
        String token = store().issue(STREAM, "media-1", Duration.ofSeconds(60));
        clock.set(Instant.parse("2026-08-10T00:02:00Z"));

        assertEquals(TokenValidationResult.EXPIRED, store().validateAndConsume(token, STREAM, "media-1"));
    }

    @Test
    void purgeExpiredRemovesExpiredTokens() {
        store().issue(STREAM, "media-1", Duration.ofSeconds(60));
        clock.set(Instant.parse("2026-08-10T00:02:00Z"));
        store().issue(STREAM, "media-1", Duration.ofSeconds(60));

        int purged = store().purgeExpired();
        assertEquals(1, purged);
    }
}
