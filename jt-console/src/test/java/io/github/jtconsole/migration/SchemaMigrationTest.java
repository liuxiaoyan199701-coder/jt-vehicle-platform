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
                List.of(new V1TenancySchemaMigration(), new V2DefaultTenantMigration(), failing));

        assertThatThrownBy(runner::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v99");

        // 前两步已提交，失败的第三步整体回滚且版本号停在 2。
        assertThat(userVersion()).isEqualTo(2L);
        assertThat(scalar("""
                SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'half_applied'
                """)).isZero();
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

    private long tenantIdOf(String table, String keyColumn, String key) {
        Long value = jdbc.sql("SELECT tenant_id FROM " + table + " WHERE " + keyColumn + " = ?")
                .param(key).query(Long.class).single();
        return value == null ? 0L : value;
    }
}
