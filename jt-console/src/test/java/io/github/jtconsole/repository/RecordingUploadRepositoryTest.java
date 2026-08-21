package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.domain.RecordingUploadTask;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class RecordingUploadRepositoryTest {
    private RecordingUploadRepository tasks;

    @BeforeEach
    void createDatabase() throws Exception {
        Path database = Files.createTempFile("jt-console-recording-upload-", ".db");
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
                VALUES ('device-1', '测试一', 1, 1, '2026-01-01', '2026-01-01'),
                       ('device-2', '测试二', 1, 2, '2026-01-01', '2026-01-01')
                """).update();
        tasks = new RecordingUploadRepository(jdbc);
    }

    @Test
    void stateMachineIsIdempotentAndDoesNotRegressAfterCompletion() {
        tasks.insert(task("task-1", 1L, "device-1"));
        tasks.markDispatched("task-1", 41, "2026-08-21T01:30:00.000+08:00", now());
        assertThat(tasks.attachFile("task-1", "evidence.mp4", 10, "http://media/file", "video/mp4", now()))
                .isEqualTo(1);

        assertThat(tasks.markTerminalCompleted("device-1", 41, 0, now())).isEqualTo(1);
        assertThat(tasks.markTerminalCompleted("device-1", 41, 0, now())).isEqualTo(1);
        tasks.attachFile("task-1", "evidence.mp4", 10, "http://media/file", "video/mp4", now());

        RecordingUploadTask completed = tasks.findByIdInternal("task-1").orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.resultCode()).isZero();
        assertThat(completed.fileName()).isEqualTo("evidence.mp4");
        assertThat(tasks.markTerminalCompleted("device-1", 41, 1, now())).isZero();
    }

    @Test
    void failedTaskCannotBeRevivedByLateFileAndTenantScopeIsEnforced() {
        tasks.insert(task("task-2", 2L, "device-2"));
        tasks.markDispatched("task-2", 42, "2026-08-21T01:30:00.000+08:00", now());
        tasks.markTerminalCompleted("device-2", 42, 1, now());
        tasks.attachFile("task-2", "late.mp4", 5, "http://media/late", "video/mp4", now());

        assertThat(tasks.findByIdInternal("task-2").orElseThrow().status()).isEqualTo("FAILED");
        assertThat(tasks.findByDevice("device-2", 10, DataScope.tenantWide(1))).isEmpty();
        assertThat(tasks.findByDevice("device-2", 10, DataScope.tenantWide(2))).hasSize(1);
    }

    private static RecordingUploadTask task(String id, Long tenantId, String deviceId) {
        return new RecordingUploadTask(id, tenantId, deviceId, null, 1,
                "2026-08-21T08:00:00.000+08:00", "2026-08-21T08:10:00.000+08:00",
                3, 1, 0, 7, "CREATED", null, null,
                null, null, null, null, now(), now(), null);
    }

    private static String now() { return "2026-08-21T09:00:00.000+08:00"; }
}
