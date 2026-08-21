package io.github.jtconsole.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.domain.ConnectionEvent;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class ConnectionEventIngestionServiceTest {
    @Test
    void attributesKnownDeviceAndLeavesUnknownDeviceUnassigned() {
        ConnectionEventRepository repository = mock(ConnectionEventRepository.class);
        DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
        when(ownership.find("known")).thenReturn(Optional.of(new DeviceOwnershipCache.Ownership(7L, null)));
        when(ownership.find("unknown")).thenReturn(Optional.empty());
        ConnectionEventIngestionService service = new ConnectionEventIngestionService(repository, ownership);

        service.handle(envelope("e-known", "known", Map.of("kind", "REGISTER_RESULT", "reasonCode", 4,
                "reason", "数据库中无该终端", "eventTime", "2026-08-21T00:00:00Z")));
        service.handle(envelope("e-unknown", "unknown", Map.of("kind", "CONNECTED",
                "eventTime", "2026-08-21T00:00:00Z")));

        verify(repository).insertIgnore(argThat(event -> Long.valueOf(7L).equals(event.tenantId())
                && event.eventTime().endsWith("+08:00") && event.repeatCount() == 1));
        verify(repository).insertIgnore(argThat(event -> event.tenantId() == null
                && event.deviceId().equals("unknown")));
    }

    @Test
    void redeliveryIsIdempotentTimesAreNormalizedAndCleanupOnlyDeletesExpired() throws Exception {
        DataSource dataSource = database();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ConnectionEventRepository repository = new ConnectionEventRepository(jdbc);
        DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
        when(ownership.find("known")).thenReturn(
                Optional.of(new DeviceOwnershipCache.Ownership(7L, null)));
        ConnectionEventIngestionService service = new ConnectionEventIngestionService(repository, ownership);
        MessageEnvelope delivered = envelope("same-event", "known", Map.of(
                "kind", "REGISTER_RESULT", "reasonCode", 4,
                "eventTime", "2026-08-21T00:00:00Z"));

        service.handle(delivered);
        service.handle(delivered);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM connection_event WHERE event_id = 'same-event'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT event_time FROM connection_event WHERE event_id = 'same-event'")
                .query(String.class).single()).isEqualTo("2026-08-21T08:00:00.000+08:00");
        assertThat(jdbc.sql("SELECT received_at FROM connection_event WHERE event_id = 'same-event'")
                .query(String.class).single()).isEqualTo("2026-08-21T08:00:01.000+08:00");

        Instant now = Instant.now();
        repository.insertIgnore(event("expired", now.minus(15, ChronoUnit.DAYS)));
        repository.insertIgnore(event("fresh", now.minus(13, ChronoUnit.DAYS)));
        assertThat(repository.deleteOlderThan(now.minus(14, ChronoUnit.DAYS), 100)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT event_id FROM connection_event WHERE event_id IN ('expired', 'fresh')")
                .query(String.class).list()).containsExactly("fresh");
    }

    private static ConnectionEvent event(String eventId, Instant time) {
        String value = io.github.jtconsole.config.Timestamps.of(time);
        return new ConnectionEvent(0, eventId, "cleanup-device", null, "CONNECTED",
                null, null, null, 1, value, value);
    }

    private static DataSource database() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + Files.createTempFile("connection-ingest-", ".db")
                .toAbsolutePath().toString().replace('\\', '/'));
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        return dataSource;
    }

    private static MessageEnvelope envelope(String eventId, String deviceId, Map<String, Object> payload) {
        return new MessageEnvelope(eventId, deviceId, 0x10001L, 0, "JT/T 808-2019",
                "2026-08-21T00:00:01Z", "signal-1", "connection", payload);
    }
}
