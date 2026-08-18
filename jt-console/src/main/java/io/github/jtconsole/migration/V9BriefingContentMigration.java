package io.github.jtconsole.migration;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v9：给 {@code ai_report} 增加 {@code content_json}，存放结构化的看板要点。
 *
 * <p><b>为什么不复用 {@code content_md}</b>：那一列的命名承诺了内容是 markdown，而看板要点必须是
 * 结构化的——每条要带严重度、涉及的设备号与导航目标，因为读取时要**按调用者的数据范围逐条过滤**，
 * 而 markdown 无法过滤。把 JSON 塞进一个名叫 {@code _md} 的列能跑，但下一个读到这段代码的人
 * 会按名字去理解它，然后写出错误的解析。
 *
 * <p>{@code content_md} 保留不动：它是 v4 为「每日运营简报」预留的，那个功能仍未实现，
 * 将来仍可能按原意使用。两列各自表意，互不干扰。
 */
@Component
public class V9BriefingContentMigration implements SchemaMigration {

    private static final String TABLE = "ai_report";

    @Override
    public int version() {
        return 9;
    }

    @Override
    public String description() {
        return "ai_report 增加 content_json 用于存放结构化看板要点";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        addColumnIfMissing(jdbc, TABLE, "content_json", "TEXT");
        // 生成耗时记进来：简报是定时任务产出的，慢下来时没人会盯着看，
        // 只有留了这个数才能在事后发现「什么时候开始变慢的」。
        addColumnIfMissing(jdbc, TABLE, "generated_ms", "INTEGER");
    }

    /**
     * SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}；版本号已保证迁移只跑一次，
     * 这里再查一次列清单是为了容忍手工改过库的环境。
     */
    private static void addColumnIfMissing(JdbcClient jdbc, String table, String column, String type) {
        List<String> existing = jdbc.sql("SELECT name FROM pragma_table_info(?)")
                .param(table)
                .query(String.class)
                .list();
        if (existing.stream().anyMatch(name -> name.equalsIgnoreCase(column))) {
            return;
        }
        // 表名与列名来自本类常量，不含外部输入。
        jdbc.sql("ALTER TABLE %s ADD COLUMN %s %s"
                .formatted(table, column, type.toUpperCase(Locale.ROOT))).update();
    }
}
