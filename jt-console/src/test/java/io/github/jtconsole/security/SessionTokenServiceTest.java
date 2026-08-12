package io.github.jtconsole.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.config.ConsoleProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionTokenServiceTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private MutableClock clock;
    private SessionTokenService tokens;

    @BeforeEach
    void setUp() {
        ConsoleProperties properties = new ConsoleProperties();
        properties.getSecurity().setAccessTokenTtl(Duration.ofSeconds(10));
        properties.getSecurity().setRefreshTokenTtl(Duration.ofMinutes(1));
        clock = new MutableClock(Instant.parse("2026-08-11T12:00:00Z"));
        tokens = new SessionTokenService(properties, clock, new SecureRandom());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void accessTokenExpiresButRefreshTokenCanStillRotate() {
        SessionTokenService.TokenPair issued = tokens.issue("admin");

        assertThat(tokens.validateAccessToken(issued.token())).contains("admin");
        clock.advance(Duration.ofSeconds(10));

        assertThat(tokens.validateAccessToken(issued.token())).isEmpty();
        SessionTokenService.TokenPair rotated =
                tokens.rotateRefreshToken(issued.refreshToken()).orElseThrow();
        assertThat(tokens.validateAccessToken(rotated.token())).contains("admin");
        assertThat(rotated.token()).isNotEqualTo(issued.token());
    }

    @Test
    void refreshRotationRejectsReplay() {
        SessionTokenService.TokenPair issued = tokens.issue("admin");

        assertThat(tokens.rotateRefreshToken(issued.refreshToken())).isPresent();
        assertThat(tokens.rotateRefreshToken(issued.refreshToken())).isEmpty();
        assertThat(tokens.validateAccessToken(issued.token())).isEmpty();
    }

    @Test
    void concurrentRefreshHasAtMostOneWinner() throws Exception {
        SessionTokenService.TokenPair issued = tokens.issue("admin");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<SessionTokenService.TokenPair>>> attempts =
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(index -> executor.submit(() -> {
                            start.await();
                            return tokens.rotateRefreshToken(issued.refreshToken());
                        }))
                        .toList();

        start.countDown();
        long winners = 0;
        for (Future<Optional<SessionTokenService.TokenPair>> attempt : attempts) {
            if (attempt.get().isPresent()) {
                winners++;
            }
        }
        assertThat(winners).isEqualTo(1);
    }

    @Test
    void logoutRevokesBothCredentials() {
        SessionTokenService.TokenPair issued = tokens.issue("admin");

        assertThat(tokens.revokeSessionForAccessToken(issued.token())).isTrue();
        assertThat(tokens.validateAccessToken(issued.token())).isEmpty();
        assertThat(tokens.rotateRefreshToken(issued.refreshToken())).isEmpty();
    }

    @Test
    void authenticatedHandleRevokesCurrentCredentialsAfterRefreshRotation() {
        SessionTokenService.TokenPair issued = tokens.issue("admin");
        SessionTokenService.AuthenticatedSession authentication =
                tokens.validateAccessSession(issued.token()).orElseThrow();

        SessionTokenService.TokenPair rotated =
                tokens.rotateRefreshToken(issued.refreshToken()).orElseThrow();
        assertThat(authentication.state())
                .isEqualTo(SessionTokenService.AuthenticationState.ACCESS_CREDENTIAL_REVOKED);

        assertThat(tokens.revokeSession(authentication)).isTrue();
        assertThat(authentication.state())
                .isEqualTo(SessionTokenService.AuthenticationState.SESSION_REVOKED);
        assertThat(tokens.validateAccessToken(rotated.token())).isEmpty();
        assertThat(tokens.rotateRefreshToken(rotated.refreshToken())).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
