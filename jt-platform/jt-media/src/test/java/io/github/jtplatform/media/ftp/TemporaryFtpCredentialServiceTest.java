package io.github.jtplatform.media.ftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporaryFtpCredentialServiceTest {
    @TempDir Path root;

    @Test
    void credentialsAreUniqueTaskScopedAndPlaintextIsNotRetainedInUser() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        TemporaryFtpCredentialService service = service(clock, Duration.ofMinutes(5));

        var first = service.issue("task-1", "device-1");
        var second = service.issue("task-2", "device-1");

        assertThat(first.username()).isNotEqualTo(second.username());
        assertThat(first.password()).isNotEqualTo(second.password());
        assertThat(service.getUserByName(first.username()).getPassword()).isEmpty();
        assertThat(service.authenticate(new UsernamePasswordAuthentication(
                first.username(), first.password())).getName()).isEqualTo(first.username());
        assertThatThrownBy(() -> service.authenticate(new UsernamePasswordAuthentication(
                first.username(), second.password())))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void completionImmediatelyRevokesCredential() throws Exception {
        TemporaryFtpCredentialService service = service(
                new MutableClock(Instant.parse("2026-08-21T00:00:00Z")), Duration.ofMinutes(5));
        var issued = service.issue("task-1", "device-1");
        service.bindCommand("task-1", "device-1", 41);

        service.completeCommand("device-1", 41);

        assertThat(service.isActive(issued.username())).isFalse();
        assertThatThrownBy(() -> service.authenticate(new UsernamePasswordAuthentication(
                issued.username(), issued.password())))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void timeoutRevokesCredentialWithoutAnyFixedFallbackAccount() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        TemporaryFtpCredentialService service = service(clock, Duration.ofSeconds(30));
        var issued = service.issue("task-timeout", "device-1");

        clock.advance(Duration.ofSeconds(31));

        assertThat(service.purgeExpired()).isEqualTo(1);
        assertThat(service.getAllUserNames()).isEmpty();
        assertThat(service.doesExist("admin")).isFalse();
        assertThat(service.isActive(issued.username())).isFalse();
    }

    private TemporaryFtpCredentialService service(Clock clock, Duration ttl) {
        RecordingFtpProperties properties = new RecordingFtpProperties();
        properties.setRoot(root);
        properties.setCredentialTtl(ttl);
        return new TemporaryFtpCredentialService(properties, clock, "192.0.2.10");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
