package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** v17：连接诊断事件及其按设备、租户的时间索引。 */
@Component
public class V17ConnectionEventsMigration implements SchemaMigration {

    @Override
    public int version() {
        return 17;
    }

    @Override
    public String description() {
        return "建 connection_event 连接诊断事件表";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS connection_event (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_id      TEXT NOT NULL UNIQUE,
                    device_id     TEXT NOT NULL,
                    tenant_id     INTEGER,
                    kind          TEXT NOT NULL,
                    reason_code   INTEGER,
                    reason        TEXT,
                    remote_addr   TEXT,
                    repeat_count  INTEGER NOT NULL DEFAULT 1,
                    event_time    TEXT NOT NULL,
                    received_at   TEXT NOT NULL
                )
                """).update();
        jdbc.sql("""
                CREATE INDEX IF NOT EXISTS idx_connection_device_time
                ON connection_event (device_id, event_time DESC)
                """).update();
        jdbc.sql("""
                CREATE INDEX IF NOT EXISTS idx_connection_tenant_time
                ON connection_event (tenant_id, event_time DESC)
                """).update();
    }
}
