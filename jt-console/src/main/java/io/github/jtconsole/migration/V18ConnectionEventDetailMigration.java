package io.github.jtconsole.migration;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v18：给 {@code connection_event} 增加 {@code detail}，存链路事件的结构化补充信息。
 *
 * <p>指令结局事件存 {@code {"commandMsgId","outcome","resultCode"}}，无流到达事件存
 * {@code {"channel","streamKind","waitedMs","mediaInstanceId"}}。两类事件字段集不同，
 * 因此用一列 JSON 文本而不是各开专用列——专用列会有一半永远为 NULL，且下一类事件又要加列。
 * 只做「按 kind 过滤后在应用侧解析」，不在 SQL 内做 JSON 查询，TEXT 足够。
 * 存量连接事件此列为 NULL。
 */
@Component
public class V18ConnectionEventDetailMigration implements SchemaMigration {

    @Override
    public int version() {
        return 18;
    }

    @Override
    public String description() {
        return "connection_event 增加 detail 结构化补充列";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        addColumnIfMissing(jdbc, "connection_event", "detail", "TEXT");
    }

    private static void addColumnIfMissing(JdbcClient jdbc, String table, String column, String type) {
        List<String> existing = jdbc.sql("SELECT name FROM pragma_table_info(?)")
                .param(table)
                .query(String.class)
                .list();
        if (existing.stream().anyMatch(name -> name.equalsIgnoreCase(column))) {
            return;
        }
        jdbc.sql("ALTER TABLE %s ADD COLUMN %s %s"
                .formatted(table, column, type.toUpperCase(Locale.ROOT))).update();
    }
}
