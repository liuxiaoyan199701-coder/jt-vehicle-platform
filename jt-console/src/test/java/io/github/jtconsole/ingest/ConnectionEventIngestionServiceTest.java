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
import java.util.LinkedHashMap;
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

    /**
     * 锁定两类链路事件的 detail 字段集：字段要变，必须先改这个测试。
     * 网关与控制台是分开部署的，字段悄悄改名会让体检的诊断静默退化成「查不到」。
     */
    @Test
    void linkEventDetailFieldsArePersistedVerbatimForBothKinds() throws Exception {
        DataSource dataSource = database();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ConnectionEventRepository repository = new ConnectionEventRepository(jdbc);
        DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
        when(ownership.find("known")).thenReturn(
                Optional.of(new DeviceOwnershipCache.Ownership(7L, null)));
        ConnectionEventIngestionService service =
                new ConnectionEventIngestionService(repository, ownership);

        service.handle(envelope("cmd-1", "known", Map.of(
                "kind", "COMMAND_RESULT", "reasonCode", 3, "eventTime", "2026-08-23T00:00:00Z",
                "detail", new LinkedHashMap<>(Map.of(
                        "commandMsgId", "0x8801", "outcome", "REJECTED", "resultCode", 3)))));
        service.handle(envelope("stream-1", "known", Map.of(
                "kind", "STREAM_NOT_ARRIVED", "eventTime", "2026-08-23T00:00:00Z",
                "detail", new LinkedHashMap<>(Map.of(
                        "channel", 3, "streamKind", "sub",
                        "waitedMs", 30000, "mediaInstanceId", "media-2")))));

        assertThat(detailOf(jdbc, "cmd-1")).isEqualTo(Map.of(
                "commandMsgId", "0x8801", "outcome", "REJECTED", "resultCode", 3));
        assertThat(detailOf(jdbc, "stream-1")).isEqualTo(Map.of(
                "channel", 3, "streamKind", "sub", "waitedMs", 30000, "mediaInstanceId", "media-2"));
    }

    @Test
    void eventsWithoutDetailStillLandWithTheColumnLeftEmpty() throws Exception {
        DataSource dataSource = database();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
        when(ownership.find("known")).thenReturn(
                Optional.of(new DeviceOwnershipCache.Ownership(7L, null)));
        ConnectionEventIngestionService service = new ConnectionEventIngestionService(
                new ConnectionEventRepository(jdbc), ownership);

        service.handle(envelope("plain", "known", Map.of(
                "kind", "CONNECTED", "eventTime", "2026-08-23T00:00:00Z")));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM connection_event WHERE event_id = 'plain' AND detail IS NULL")
                .query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * 部署顺序是先网关后控制台，中间那段时间控制台会收到自己还不认识的事件。
     * 未知 kind 与多余 payload 字段都必须照常落库，否则那段窗口的事件会永久丢失。
     */
    @Test
    void unknownKindsAndUnknownPayloadFieldsStillLand() throws Exception {
        DataSource dataSource = database();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
        when(ownership.find("known")).thenReturn(
                Optional.of(new DeviceOwnershipCache.Ownership(7L, null)));
        ConnectionEventIngestionService service = new ConnectionEventIngestionService(
                new ConnectionEventRepository(jdbc), ownership);

        service.handle(envelope("future-1", "known", Map.of(
                "kind", "SOME_FUTURE_KIND", "eventTime", "2026-08-23T00:00:00Z",
                "somethingNobodyParsesYet", "值", "anotherOne", 42)));

        assertThat(jdbc.sql("SELECT kind FROM connection_event WHERE event_id = 'future-1'")
                .query(String.class).single()).isEqualTo("SOME_FUTURE_KIND");
    }

    private static Map<String, Object> detailOf(JdbcClient jdbc, String eventId) throws Exception {
        String stored = jdbc.sql("SELECT detail FROM connection_event WHERE event_id = ?")
                .param(eventId).query(String.class).single();
        return new tools.jackson.databind.ObjectMapper()
                .readValue(stored, new tools.jackson.core.type.TypeReference<>() { });
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
