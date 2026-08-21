package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v13：建司机档案、0702 身份事件与车辆驾驶区间三张表。
 *
 * <p>时间列口径与全平台一致：device_time 走设备本地时间补 +08:00，received_at 走归一化。
 * 事件表带 event_id 唯一键做幂等兜底；区间表「当前驾驶员」即 ended_at IS NULL 的那段。
 */
@Component
public class V13DriverManagementMigration implements SchemaMigration {

    @Override
    public int version() {
        return 13;
    }

    @Override
    public String description() {
        return "建 driver / driver_identity_event / vehicle_driver_session 支持驾驶员管理";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS driver (
                            id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                            tenant_id             INTEGER NOT NULL,
                            department_id         INTEGER,
                            name                  TEXT NOT NULL,
                            id_card               TEXT NOT NULL,
                            license_no            TEXT NOT NULL,
                            institution           TEXT,
                            license_valid_period  TEXT,
                            phone                 TEXT,
                            remark                TEXT,
                            created_at            TEXT NOT NULL,
                            updated_at            TEXT NOT NULL
                        )
                        """).update();
        jdbc.sql("CREATE INDEX IF NOT EXISTS idx_driver_tenant ON driver (tenant_id)").update();
        jdbc.sql("CREATE INDEX IF NOT EXISTS idx_driver_license ON driver (license_no)").update();

        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS driver_identity_event (
                            id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                            event_id              TEXT NOT NULL UNIQUE,
                            device_id             TEXT NOT NULL,
                            status                INTEGER NOT NULL,
                            card_status           INTEGER NOT NULL,
                            name                  TEXT,
                            license_no            TEXT,
                            institution           TEXT,
                            license_valid_period  TEXT,
                            id_card               TEXT,
                            driver_id             INTEGER,
                            device_time           TEXT NOT NULL,
                            received_at           TEXT NOT NULL
                        )
                        """).update();
        jdbc.sql("""
                        CREATE INDEX IF NOT EXISTS idx_driver_event_device_time
                        ON driver_identity_event (device_id, device_time DESC)
                        """).update();

        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS vehicle_driver_session (
                            id             INTEGER PRIMARY KEY AUTOINCREMENT,
                            device_id      TEXT NOT NULL,
                            driver_id      INTEGER,
                            driver_name    TEXT,
                            license_no     TEXT,
                            started_at     TEXT NOT NULL,
                            ended_at       TEXT,
                            source         TEXT NOT NULL,
                            created_at     TEXT NOT NULL,
                            updated_at     TEXT NOT NULL
                        )
                        """).update();
        jdbc.sql("""
                        CREATE INDEX IF NOT EXISTS idx_driver_session_device_ended
                        ON vehicle_driver_session (device_id, ended_at)
                        """).update();
    }
}
