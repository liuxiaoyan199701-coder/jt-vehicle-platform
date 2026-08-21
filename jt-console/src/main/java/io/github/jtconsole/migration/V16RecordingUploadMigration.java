package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** v16：JT/T 1078 录像文件上传任务留痕。 */
@Component
public class V16RecordingUploadMigration implements SchemaMigration {
    @Override public int version() { return 16; }
    @Override public String description() { return "建 recording_upload_task 表支持 0x9206/0x1206 上传闭环"; }

    @Override
    public void apply(JdbcClient jdbc) {
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS recording_upload_task (
                    id                TEXT PRIMARY KEY,
                    tenant_id         INTEGER,
                    device_id         TEXT NOT NULL,
                    command_serial_no INTEGER,
                    channel_no        INTEGER NOT NULL,
                    start_at          TEXT NOT NULL,
                    end_at            TEXT NOT NULL,
                    media_type        INTEGER NOT NULL,
                    stream_type       INTEGER NOT NULL,
                    storage_type      INTEGER NOT NULL,
                    condition_bits    INTEGER NOT NULL,
                    status            TEXT NOT NULL,
                    result_code       INTEGER,
                    credential_expires_at TEXT,
                    file_name         TEXT,
                    file_size         INTEGER,
                    access_address    TEXT,
                    content_type      TEXT,
                    created_at        TEXT NOT NULL,
                    updated_at        TEXT NOT NULL,
                    completed_at      TEXT
                )
                """).update();
        jdbc.sql("""
                CREATE INDEX IF NOT EXISTS idx_recording_upload_device_created
                ON recording_upload_task (device_id, created_at DESC)
                """).update();
        jdbc.sql("""
                CREATE INDEX IF NOT EXISTS idx_recording_upload_command
                ON recording_upload_task (device_id, command_serial_no, created_at DESC)
                """).update();
        jdbc.sql("""
                CREATE INDEX IF NOT EXISTS idx_recording_upload_tenant_created
                ON recording_upload_task (tenant_id, created_at DESC)
                """).update();
    }
}
