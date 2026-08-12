package io.github.jtplatform.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryStreamTokenStoreTest {
    private static final StreamKey STREAM = new StreamKey("device-1", 1, StreamKind.MAIN);

    @Test
    void tokenIsBoundToStreamAndInstanceAndCanOnlyBeUsedOnce() {
        InMemoryStreamTokenStore store = new InMemoryStreamTokenStore(new SecureRandom(),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));
        String token = store.issue(STREAM, "media-1", Duration.ofSeconds(60));

        assertEquals(TokenValidationResult.WRONG_STREAM, store.validateAndConsume(token,
                new StreamKey("device-1", 2, StreamKind.MAIN), "media-1"));
        assertEquals(TokenValidationResult.WRONG_INSTANCE, store.validateAndConsume(token, STREAM, "media-2"));
        assertEquals(TokenValidationResult.VALID, store.validateAndConsume(token, STREAM, "media-1"));
        assertEquals(TokenValidationResult.REPLAYED, store.validateAndConsume(token, STREAM, "media-1"));
    }

    @Test
    void expiredTokenIsRejected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        InMemoryStreamTokenStore store = new InMemoryStreamTokenStore(new SecureRandom(), clock);
        String token = store.issue(STREAM, "media-1", Duration.ofSeconds(60));
        clock.set(Instant.parse("2026-08-10T00:02:00Z"));

        assertEquals(TokenValidationResult.EXPIRED, store.validateAndConsume(token, STREAM, "media-1"));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
