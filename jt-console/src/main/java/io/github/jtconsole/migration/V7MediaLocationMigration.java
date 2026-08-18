package io.github.jtconsole.migration;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v7：给 {@code media_file} 补上抓拍位置，并加一条支持跨车辆时间范围查询的索引。
 *
 * <p><b>为什么现在才补</b>：网关在 0x0801 的投递信封里一直带着 {@code location}（见
 * {@code ProtocolPayloadMapper.enrichMultimedia}），但 {@code MediaIngestionService} 只挑了文件
 * 标识那几个字段落库，位置被直接丢弃。于是抓拍在库里只有「哪台车、什么时候、哪个通道」，
 * 没有「在哪」——既画不到地图上，也无法和告警的发生地对照。
 *
 * <p><b>为什么是四列而不是两列</b>：与 {@code alarm_event}、{@code track_point} 保持同一形态，
 * WGS-84 原值与 GCJ-02 偏移值并存。原值是设备报上来的事实，偏移值是渲染用的派生量；只存其一
 * 会在换地图供应商或对外导出时抓瞎。
 *
 * <p><b>纯加列，不回填</b>：历史行的四列为 NULL，前端按「无位置」降级展示。不去猜历史抓拍的
 * 位置——那需要拿 {@code captured_at} 去 {@code track_point} 做时间近邻匹配，匹配出来的是
 * 「那个时刻车在哪」而不是「照片拍于哪」，两者在设备时钟漂移时并不等价，写进库就再也分不清了。
 */
@Component
public class V7MediaLocationMigration implements SchemaMigration {

    private static final String TABLE = "media_file";

    @Override
    public int version() {
        return 7;
    }

    @Override
    public String description() {
        return "media_file 补充抓拍经纬度并建立跨车辆时间索引";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        addColumnIfMissing(jdbc, TABLE, "lat", "REAL");
        addColumnIfMissing(jdbc, TABLE, "lng", "REAL");
        addColumnIfMissing(jdbc, TABLE, "gcj_lat", "REAL");
        addColumnIfMissing(jdbc, TABLE, "gcj_lng", "REAL");

        // 既有索引是 (device_id, captured_at DESC, id DESC)，多媒体页的「不限车辆、按时间段查」
        // 用不上它——前导列是 device_id，缺了它只能全表扫。
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_media_captured
                    ON media_file (captured_at DESC, id DESC)
                """);
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
        execute(jdbc, "ALTER TABLE %s ADD COLUMN %s %s"
                .formatted(table, column, type.toUpperCase(Locale.ROOT)));
    }

    private static void execute(JdbcClient jdbc, String sql) {
        jdbc.sql(sql).update();
    }
}
