package io.github.jtconsole.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jtconsole.support.TestSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

/** 迁移机制本身的行为：升级归属、版本推进、失败回滚。 */
class SchemaMigrationTest {

    private DataSource dataSource;
    private JdbcClient jdbc;
    private PlatformTransactionManager transactions;

    @BeforeEach
    void createDatabase() throws IOException, SQLException {
        Path database = Files.createTempFile("jt-console-migration-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        dataSource = sqlite;
        jdbc = JdbcClient.create(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
    }

    @Test
    void legacyDatabaseKeepsItsDataAndJoinsTheDefaultTenant() {
        String now = Instant.now().toString();
        jdbc.sql("""
                        INSERT INTO vehicle (device_id, plate_no, plate_color, brand,
                                             channel_count, remark, created_at, updated_at)
                        VALUES ('00123', '京A00123', '蓝色', '存量车', 1, NULL, ?, ?)
                        """).param(now).param(now).update();
        jdbc.sql("""
                        INSERT INTO fleet (code, name, created_at, updated_at)
                        VALUES ('legacy', '存量车队', ?, ?)
                        """).param(now).param(now).update();

        TestSchema.migrate(jdbc, transactions);

        long tenantId = TestSchema.defaultTenantId(jdbc);
        assertThat(tenantIdOf("vehicle", "device_id", "00123")).isEqualTo(tenantId);
        assertThat(scalar("SELECT tenant_id FROM fleet WHERE code = 'legacy'")).isEqualTo(tenantId);
        assertThat(scalar("SELECT COUNT(*) FROM vehicle")).isEqualTo(1L);
        // 默认租户刻意不设有效期：升级当天不能有任何租户突然到期。
        assertThat(jdbc.sql("SELECT expires_at FROM tenant WHERE id = ?")
                .param(tenantId).query(String.class).optional()).isEmpty();
    }

    @Test
    void duplicateTrackPointsAreCollapsedBeforeTheUniqueIndexIsBuilt() {
        // 唯一约束之前入库的重复点：同一设备同一设备时间被写了三次
        String received = Instant.now().toString();
        for (int copy = 0; copy < 3; copy++) {
            insertTrackPoint("00123", "2026-08-11T10:00:00", received);
        }
        insertTrackPoint("00123", "2026-08-11T10:01:00", received);
        // 没有设备时间的点无从判断是否重复，必须原样保留
        insertTrackPoint("00123", null, received);
        insertTrackPoint("00123", null, received);

        TestSchema.migrate(jdbc, transactions);

        assertThat(scalar("SELECT COUNT(*) FROM track_point WHERE device_time = '2026-08-11T10:00:00'"))
                .isEqualTo(1L);
        assertThat(scalar("SELECT COUNT(*) FROM track_point WHERE device_time IS NULL")).isEqualTo(2L);
        assertThat(scalar("SELECT COUNT(*) FROM track_point")).isEqualTo(4L);
    }

    @Test
    void theUniqueIndexKeepsLaterDuplicatesOut() {
        TestSchema.migrate(jdbc, transactions);
        String received = Instant.now().toString();

        insertTrackPoint("00123", "2026-08-11T10:00:00", received);
        // 补传批次里重复的那个点：约束把它挡掉，而不是让轨迹出现重影
        int second = insertOrIgnoreTrackPoint("00123", "2026-08-11T10:00:00", received);

        assertThat(second).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM track_point")).isEqualTo(1L);
    }

    @Test
    void freshDatabaseReachesLatestVersionAndDoesNotRepeatMigrations() {
        TestSchema.migrate(jdbc, transactions);
        long versionAfterFirstRun = userVersion();
        long tenantsAfterFirstRun = scalar("SELECT COUNT(*) FROM tenant");

        TestSchema.migrate(jdbc, transactions);

        assertThat(userVersion()).isEqualTo(versionAfterFirstRun);
        assertThat(scalar("SELECT COUNT(*) FROM tenant")).isEqualTo(tenantsAfterFirstRun);
        assertThat(scalar("SELECT COUNT(*) FROM plan")).isEqualTo(1L);
    }

    @Test
    void failedMigrationRollsBackAndLeavesTheVersionUnchanged() {
        SchemaMigration failing = new SchemaMigration() {
            @Override
            public int version() {
                return 99;
            }

            @Override
            public String description() {
                return "故意失败的迁移";
            }

            @Override
            public void apply(JdbcClient client) {
                client.sql("CREATE TABLE half_applied (id INTEGER PRIMARY KEY)").update();
                throw new IllegalStateException("boom");
            }
        };
        SchemaMigrationRunner runner = new SchemaMigrationRunner(
                jdbc, transactions,
                List.of(new V1TenancySchemaMigration(), new V2DefaultTenantMigration(),
                        new V3TrackPointUniquenessMigration(), new V4AiSchemaMigration(),
                        new V5SessionPersistenceMigration(), failing));

        assertThatThrownBy(runner::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v99");

        // 前几步已提交，失败的那一步整体回滚且版本号停在最后一个成功的版本。
        assertThat(userVersion()).isEqualTo(5L);
        assertThat(scalar("""
                SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'half_applied'
                """)).isZero();
    }

    @Test
    void aiTablesArriveAndExistingPlansStayUnlimited() {
        migrateThroughV3();
        String now = Instant.now().toString();
        jdbc.sql("""
                        INSERT INTO plan (name, max_vehicles, max_accounts, price_cents,
                                          period_months, enabled, remark, created_at, updated_at)
                        VALUES ('存量套餐', 10, 5, 0, 1, 1, NULL, ?, ?)
                        """).param(now).param(now).update();

        TestSchema.migrate(jdbc, transactions);

        assertThat(userVersion()).isEqualTo(13L);
        for (String table : List.of("ai_usage", "ai_conversation", "ai_message", "ai_report")) {
            assertThat(scalar("""
                    SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '%s'
                    """.formatted(table))).as(table).isEqualTo(1L);
        }
        // 0 = 不限量：升级本身不能让任何租户的 AI 用量突然受限。
        assertThat(scalar("SELECT max_ai_calls_monthly FROM plan WHERE name = '存量套餐'")).isZero();
    }

    @Test
    void theAiMigrationToleratesAColumnSomeoneAddedByHand() {
        migrateThroughV3();
        jdbc.sql("ALTER TABLE plan ADD COLUMN max_ai_calls_monthly INTEGER NOT NULL DEFAULT 0")
                .update();

        TestSchema.migrate(jdbc, transactions);

        assertThat(userVersion()).isEqualTo(13L);
    }

    /** 把库推进到加 AI 表之前的状态，用来模拟真实的升级起点。 */
    private void migrateThroughV3() {
        new SchemaMigrationRunner(jdbc, transactions,
                List.of(new V1TenancySchemaMigration(), new V2DefaultTenantMigration(),
                        new V3TrackPointUniquenessMigration()))
                .afterPropertiesSet();
    }

    @Test
    void aReportIsUniquePerTenantAndDate() {
        TestSchema.migrate(jdbc, transactions);
        String now = Instant.now().toString();
        insertReport(1L, "2026-08-14", now);

        // 幂等靠这条唯一约束兜底，而不是靠生成任务自觉。
        assertThatThrownBy(() -> insertReport(1L, "2026-08-14", now))
                .hasMessageContaining("UNIQUE");
        assertThat(scalar("SELECT COUNT(*) FROM ai_report")).isEqualTo(1L);
    }

    private void insertReport(long tenantId, String date, String now) {
        jdbc.sql("""
                        INSERT INTO ai_report (tenant_id, report_date, status, content_md,
                                               created_at, updated_at)
                        VALUES (?, ?, 'ok', '# 简报', ?, ?)
                        """)
                .param(tenantId).param(date).param(now).param(now)
                .update();
    }

    @Test
    void duplicateVersionsAreRejectedBeforeAnythingRuns() {
        assertThatThrownBy(() -> new SchemaMigrationRunner(
                jdbc, transactions,
                List.of(new V1TenancySchemaMigration(), new V1TenancySchemaMigration())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");
    }

    private long userVersion() {
        return scalar("PRAGMA user_version");
    }

    private long scalar(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0L : value;
    }

    private void insertTrackPoint(String deviceId, String deviceTime, String receivedAt) {
        jdbc.sql("""
                        INSERT INTO track_point (device_id, device_time, received_at,
                                                 lat, lng, gcj_lat, gcj_lng)
                        VALUES (?, ?, ?, 39.9, 116.4, 39.9, 116.4)
                        """)
                .param(deviceId).param(deviceTime).param(receivedAt).update();
    }

    private int insertOrIgnoreTrackPoint(String deviceId, String deviceTime, String receivedAt) {
        return jdbc.sql("""
                        INSERT INTO track_point (device_id, device_time, received_at,
                                                 lat, lng, gcj_lat, gcj_lng)
                        VALUES (?, ?, ?, 39.9, 116.4, 39.9, 116.4)
                        ON CONFLICT (device_id, device_time) DO NOTHING
                        """)
                .param(deviceId).param(deviceTime).param(receivedAt).update();
    }

    private long tenantIdOf(String table, String keyColumn, String key) {
        Long value = jdbc.sql("SELECT tenant_id FROM " + table + " WHERE " + keyColumn + " = ?")
                .param(key).query(Long.class).single();
        return value == null ? 0L : value;
    }
}
