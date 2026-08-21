package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v11：建告警规则引擎的三张表。
 *
 * <p>{@code alarm_rule} 是规则本体；{@code alarm_rule_vehicle} 是「规则 → 车辆」分配；
 * {@code alarm_rule_state} 存时长规则的窗口起点（怠速/疲劳驾驶从何时开始持续），
 * 落在库里保证重启不丢。
 */
@Component
public class V11AlarmRuleMigration implements SchemaMigration {

    @Override
    public int version() {
        return 11;
    }

    @Override
    public String description() {
        return "建 alarm_rule / alarm_rule_vehicle / alarm_rule_state 支持告警规则引擎";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS alarm_rule (
                            id                INTEGER PRIMARY KEY AUTOINCREMENT,
                            name              TEXT NOT NULL,
                            type              TEXT NOT NULL,
                            threshold_kph     REAL NOT NULL,
                            duration_minutes  INTEGER NOT NULL DEFAULT 0,
                            level             TEXT NOT NULL,
                            enabled           INTEGER NOT NULL DEFAULT 1,
                            tenant_id         INTEGER,
                            created_at        TEXT NOT NULL,
                            updated_at        TEXT NOT NULL
                        )
                        """).update();
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS alarm_rule_vehicle (
                            rule_id    INTEGER NOT NULL,
                            device_id  TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            PRIMARY KEY (rule_id, device_id)
                        )
                        """).update();
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS alarm_rule_state (
                            rule_id         INTEGER NOT NULL,
                            device_id       TEXT NOT NULL,
                            window_start_at TEXT NOT NULL,
                            updated_at      TEXT NOT NULL,
                            PRIMARY KEY (rule_id, device_id)
                        )
                        """).update();
    }
}
