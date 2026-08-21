package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** v15：电子运单原文留存。v14 已预留给连接诊断车道，不得占用。 */
@Component
public class V15WaybillMigration implements SchemaMigration {

    @Override
    public int version() {
        return 15;
    }

    @Override
    public String description() {
        return "建 waybill 表支持 0x0701 电子运单留存";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS waybill (
                            id           INTEGER PRIMARY KEY AUTOINCREMENT,
                            event_id     TEXT NOT NULL UNIQUE,
                            tenant_id    INTEGER,
                            device_id    TEXT NOT NULL,
                            reported_at  TEXT NOT NULL,
                            received_at  TEXT NOT NULL,
                            raw_base64   TEXT NOT NULL,
                            raw_length   INTEGER NOT NULL,
                            created_at   TEXT NOT NULL
                        )
                        """).update();
        jdbc.sql("""
                        CREATE INDEX IF NOT EXISTS idx_waybill_device_reported
                        ON waybill (device_id, reported_at DESC, id DESC)
                        """).update();
        jdbc.sql("""
                        CREATE INDEX IF NOT EXISTS idx_waybill_tenant_reported
                        ON waybill (tenant_id, reported_at DESC, id DESC)
                        """).update();
    }
}
