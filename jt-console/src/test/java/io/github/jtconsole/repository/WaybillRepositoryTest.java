package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class WaybillRepositoryTest {
    private JdbcClient jdbc;
    private WaybillRepository waybills;

    @BeforeEach
    void createDatabase() throws IOException, SQLException {
        Path database = Files.createTempFile("jt-console-waybill-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        jdbc = JdbcClient.create(dataSource);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, transactions);
        jdbc.sql("""
                INSERT INTO vehicle
                    (device_id, plate_no, channel_count, tenant_id, created_at, updated_at)
                VALUES ('device-1', '测试一', 1, 1, ?, ?),
                       ('device-2', '测试二', 1, 2, ?, ?)
                """).params(java.util.List.of("2026-01-01T00:00:00.000+08:00",
                        "2026-01-01T00:00:00.000+08:00",
                        "2026-01-01T00:00:00.000+08:00",
                        "2026-01-01T00:00:00.000+08:00")).update();
        waybills = new WaybillRepository(jdbc);
    }

    @Test
    void listsNewestFirstAndDecodesUtf8Preview() {
        insert("e1", "2026-08-20T10:00:00.000+08:00", "运单一".getBytes(StandardCharsets.UTF_8));
        insert("e2", "2026-08-20T11:00:00.000+08:00", "运单二".getBytes(StandardCharsets.UTF_8));

        var page = waybills.findByDevice("device-1", 1, 20, DataScope.tenantWide(1));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(item -> item.reportedAt())
                .containsExactly("2026-08-20T11:00:00.000+08:00", "2026-08-20T10:00:00.000+08:00");
        assertThat(page.items().getFirst().preview()).isEqualTo("运单二");
        assertThat(page.items().getFirst().utf8()).isTrue();
    }

    @Test
    void malformedUtf8OnlyDegradesPreview() {
        insert("bad", "2026-08-20T10:00:00.000+08:00", new byte[] {(byte) 0xC3, 0x28});

        var item = waybills.findByDevice("device-1", 1, 20, DataScope.tenantWide(1))
                .items().getFirst();

        assertThat(item.utf8()).isFalse();
        assertThat(item.preview()).contains("不是有效 UTF-8");
        assertThat(waybills.findRaw(item.id(), "device-1", DataScope.tenantWide(1)).orElseThrow().bytes())
                .containsExactly((byte) 0xC3, 0x28);
    }

    @Test
    void outOfScopeDeviceReturnsEmptyAndRawIsHidden() {
        insert("other", "device-2", "2026-08-20T10:00:00.000+08:00", new byte[] {1, 2});
        long id = jdbc.sql("SELECT id FROM waybill WHERE event_id = 'other'")
                .query(Long.class).single();

        assertThat(waybills.findByDevice("device-1", 1, 20, DataScope.tenantWide(1)).items()).isEmpty();
        assertThat(waybills.findRaw(id, "device-2", DataScope.tenantWide(1))).isEmpty();
    }

    @Test
    void duplicateEventIsIgnoredAndRetentionDeletesOldRows() {
        byte[] raw = {1, 2, 3};
        assertThat(insert("same", "2025-01-01T00:00:00.000+08:00", raw)).isTrue();
        assertThat(insert("same", "2025-01-01T00:00:00.000+08:00", raw)).isFalse();
        assertThat(waybills.deleteOlderThan("2026-01-01T00:00:00.000+08:00", 500)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM waybill").query(Integer.class).single()).isZero();
    }

    private boolean insert(String eventId, String reportedAt, byte[] raw) {
        return insert(eventId, "device-1", reportedAt, raw);
    }

    private boolean insert(String eventId, String deviceId, String reportedAt, byte[] raw) {
        String base64 = Base64.getEncoder().encodeToString(raw);
        return waybills.insertIgnore(eventId, deviceId.equals("device-1") ? 1L : 2L, deviceId, reportedAt,
                Instant.parse("2026-08-20T04:00:00Z").toString(), base64, raw.length, reportedAt);
    }
}
