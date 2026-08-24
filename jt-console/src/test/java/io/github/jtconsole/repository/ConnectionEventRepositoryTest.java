package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.ConnectionEvent;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

/** 链路事件（COMMAND_RESULT / STREAM_NOT_ARRIVED）沿用连接事件的租户条款与保留期。 */
class ConnectionEventRepositoryTest {
    private static final String COMMAND_DETAIL =
            "{\"commandMsgId\":\"0x8801\",\"outcome\":\"REJECTED\",\"resultCode\":3}";

    private ConnectionEventRepository events;

    @BeforeEach
    void createDatabase() throws Exception {
        Path database = Files.createTempFile("jt-console-connection-event-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        JdbcClient jdbc = JdbcClient.create(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        jdbc.sql("""
                INSERT INTO tenant (id, code, name, status, plan_id, created_at, updated_at)
                SELECT 2, 'tenant-2', '租户二', 'ACTIVE', id, '2026-01-01', '2026-01-01'
                FROM plan ORDER BY id LIMIT 1
                """).update();
        jdbc.sql("""
                INSERT INTO vehicle (device_id, plate_no, channel_count, tenant_id, created_at, updated_at)
                VALUES ('device-1', '测试一', 1, 1, '2026-01-01', '2026-01-01')
                """).update();
        events = new ConnectionEventRepository(jdbc);
    }

    @Test
    void detailSurvivesTheRoundTripAndOnlyTheOwningTenantSeesTheEvent() {
        events.insertIgnore(event("cmd-1", "device-1", 1L, "COMMAND_RESULT", COMMAND_DETAIL));

        assertThat(read("device-1", DataScope.tenantWide(1L)))
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.kind()).isEqualTo("COMMAND_RESULT");
                    assertThat(found.detail()).isEqualTo(COMMAND_DETAIL);
                });
        assertThat(read("device-1", DataScope.tenantWide(2L))).isEmpty();
    }

    /** 未建档设备的链路事件归 NULL 租户：租户看不到，平台管理员看得到。 */
    @Test
    void unarchivedDeviceLinkEventsAreVisibleOnlyToPlatformAdministrators() {
        events.insertIgnore(event("stream-1", "unarchived", null, "STREAM_NOT_ARRIVED",
                "{\"channel\":3,\"streamKind\":\"sub\",\"waitedMs\":30000}"));

        assertThat(read("unarchived", DataScope.tenantWide(1L))).isEmpty();
        assertThat(read("unarchived", DataScope.platform()))
                .singleElement()
                .satisfies(found -> assertThat(found.detail()).contains("\"streamKind\":\"sub\""));
    }

    @Test
    void redeliveredLinkEventIsIgnoredAndTheStoredDetailIsNotOverwritten() {
        assertThat(events.insertIgnore(event("cmd-1", "device-1", 1L, "COMMAND_RESULT", COMMAND_DETAIL)))
                .isTrue();
        assertThat(events.insertIgnore(event("cmd-1", "device-1", 1L, "COMMAND_RESULT", "{\"outcome\":\"OK\"}")))
                .isFalse();

        assertThat(read("device-1", DataScope.tenantWide(1L)))
                .singleElement()
                .satisfies(found -> assertThat(found.detail()).isEqualTo(COMMAND_DETAIL));
    }

    @Test
    void retentionCleanupRemovesExpiredLinkEventsTogetherWithTheirDetail() {
        Instant now = Instant.now();
        events.insertIgnore(event("expired", "device-1", 1L, "COMMAND_RESULT", COMMAND_DETAIL,
                now.minus(15, ChronoUnit.DAYS)));
        events.insertIgnore(event("fresh", "device-1", 1L, "COMMAND_RESULT", COMMAND_DETAIL,
                now.minus(1, ChronoUnit.DAYS)));

        assertThat(events.deleteOlderThan(now.minus(14, ChronoUnit.DAYS), 100)).isEqualTo(1);
        assertThat(read("device-1", DataScope.platform()))
                .extracting(ConnectionEvent::eventId).containsExactly("fresh");
    }

    private List<ConnectionEvent> read(String deviceId, DataScope scope) {
        return events.findByDevice(deviceId, null, null, 100, scope);
    }

    private static ConnectionEvent event(
            String eventId, String deviceId, Long tenantId, String kind, String detail) {
        return event(eventId, deviceId, tenantId, kind, detail, Instant.parse("2026-08-23T02:00:00Z"));
    }

    private static ConnectionEvent event(
            String eventId, String deviceId, Long tenantId, String kind, String detail, Instant at) {
        String value = Timestamps.of(at);
        return new ConnectionEvent(0, eventId, deviceId, tenantId, kind, null, null, null,
                1, value, value, detail);
    }
}
