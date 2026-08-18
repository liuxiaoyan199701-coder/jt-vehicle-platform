package io.github.jtconsole.migration;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v6：把全库时间戳统一成东八区带偏移的写法（{@code 2026-08-17T17:41:15.123+08:00}）。
 *
 * <p><b>为什么要做</b>：此前库里同时存在三种口径——服务端生成的是 UTC（{@code …Z}）、终端上报的
 * 设备时间是无时区本地时间、而查询边界又常写成「日期 空格 时间」。时间列是 TEXT，范围查询走字节
 * 比较，口径一混，比较结果就没有意义。实际后果是 AI 按「今天」查告警会偏 8 小时，而设备时间显示
 * 出来比真实时间早若干小时，让人误判车辆已经离线。
 *
 * <p><b>为什么必须一次转干净</b>：{@code 'Z'} 是 0x5A、{@code '+'} 是 0x2B，两种格式互相比较得到的
 * 是字节序而不是时间序。只要库里同时存在两种写法，任何跨越这条分界的范围查询都会给出错误结果，
 * 而且不报错。所以这次迁移覆盖全部时间列，并且与写入侧的切换同批上线。
 *
 * <p><b>换算规则</b>：带 {@code Z} 或已有偏移的值按时刻换算到 +08:00（时刻不变，只换表示）；
 * 无时区的值（设备时间）直接补 {@code +08:00}——它本来就是终端本地时间，补偏移是把一直以来的隐含
 * 约定写明确，不改变语义。
 */
@Component
public class V6BeijingTimestampMigration implements SchemaMigration {

    /**
     * 全部时间列，取自实际库结构而非 schema 文件——历史上有若干列是由前几版迁移补上的，
     * 只看 {@code schema.sql} 会漏。
     */
    private static final Map<String, List<String>> COLUMNS = Map.ofEntries(
            Map.entry("vehicle", List.of("created_at", "updated_at")),
            Map.entry("device_status", List.of("last_seen_at", "device_time", "updated_at")),
            Map.entry("track_point", List.of("device_time", "received_at")),
            Map.entry("processed_event", List.of("created_at")),
            Map.entry("alarm_event", List.of("occurred_at", "last_occurred_at",
                    "acknowledged_at", "closed_at", "created_at", "updated_at")),
            Map.entry("alarm_condition_state", List.of("last_seen_at", "updated_at")),
            Map.entry("geofence", List.of("created_at", "updated_at")),
            Map.entry("geofence_vehicle", List.of("created_at")),
            Map.entry("geofence_presence", List.of("updated_at")),
            Map.entry("vehicle_daily_stat", List.of("last_device_time", "updated_at")),
            Map.entry("fleet", List.of("created_at", "updated_at")),
            Map.entry("fleet_vehicle", List.of("assigned_at")),
            Map.entry("media_file", List.of("captured_at")),
            Map.entry("device_attribute", List.of("updated_at")),
            Map.entry("tenant", List.of("expires_at", "created_at", "updated_at")),
            Map.entry("account", List.of("last_login_at", "created_at", "updated_at")),
            Map.entry("permission", List.of("updated_at")),
            Map.entry("role", List.of("created_at", "updated_at")),
            Map.entry("department", List.of("created_at", "updated_at")),
            Map.entry("position", List.of("created_at", "updated_at")),
            Map.entry("plan", List.of("created_at", "updated_at")),
            Map.entry("tenant_order", List.of("previous_expires_at", "new_expires_at",
                    "created_at")),
            Map.entry("tenant_config", List.of("updated_at")),
            Map.entry("tenant_registration", List.of("reviewed_at", "created_at", "updated_at")),
            Map.entry("audit_log", List.of("occurred_at")),
            Map.entry("ai_usage", List.of("created_at")),
            Map.entry("ai_conversation", List.of("created_at", "updated_at")),
            Map.entry("ai_message", List.of("created_at")),
            Map.entry("ai_report", List.of("created_at", "updated_at")),
            Map.entry("user_session", List.of("issued_at", "access_expires_at",
                    "refresh_expires_at")));

    @Override
    public int version() {
        return 6;
    }

    @Override
    public String description() {
        return "全库时间戳统一为东八区带偏移写法";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        for (Map.Entry<String, List<String>> table : COLUMNS.entrySet()) {
            if (!tableExists(jdbc, table.getKey())) {
                // 部分表由后续版本引入，或在旧库里根本没建过；缺表不是错误。
                continue;
            }
            for (String column : table.getValue()) {
                convert(jdbc, table.getKey(), column);
            }
        }
    }

    /**
     * 就地换算一列。
     *
     * <p>用 SQLite 自己的日期函数而不是把数据读进 JVM 再写回：几十张表逐行往返在启动期太慢，
     * 而这里的换算规则简单到用 SQL 表达即可。
     *
     * <p>三条分支互斥：已经是 {@code +08:00} 的跳过（迁移可重复执行）；带 {@code Z} 或其它偏移的
     * 按时刻换算；无时区的直接补偏移。
     */
    private static void convert(JdbcClient jdbc, String table, String column) {
        jdbc.sql("""
                        UPDATE %s
                        SET %s = strftime('%%Y-%%m-%%dT%%H:%%M:%%f', datetime(%s, '+8 hours'))
                                 || '+08:00'
                        WHERE %s IS NOT NULL
                          AND %s <> ''
                          AND %s LIKE '%%Z'
                        """.formatted(table, column, column, column, column, column))
                .update();
        // 无时区的值（设备时间）不换算时刻，只补偏移。仍然走一遍 strftime 是为了统一补齐到毫秒：
        // 「有的带毫秒、有的不带」会让长度不一，而 '+'(0x2B) 排在 '.'(0x2E) 之前，
        // 范围查询会在边界上悄悄漏掉不带毫秒的那些行。
        jdbc.sql("""
                        UPDATE %s
                        SET %s = strftime('%%Y-%%m-%%dT%%H:%%M:%%f', replace(%s, ' ', 'T'))
                                 || '+08:00'
                        WHERE %s IS NOT NULL
                          AND %s <> ''
                          AND %s NOT LIKE '%%+__:__'
                          AND %s NOT LIKE '%%Z'
                          AND strftime('%%Y', replace(%s, ' ', 'T')) IS NOT NULL
                        """.formatted(table, column, column, column, column, column, column,
                        column))
                .update();
    }

    private static boolean tableExists(JdbcClient jdbc, String table) {
        Integer count = jdbc.sql(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")
                .param(table)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }
}
