package io.github.jtconsole.migration;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v10：给 {@code geofence} 增加 {@code shape} 与 {@code points}，支持多边形/矩形/路线围栏。
 *
 * <p>{@code shape} 缺省 'circle'，存量圆形围栏无需回填；{@code points} 存 GCJ-02 顶点序列的
 * JSON 文本（{@code [[lat,lng],...]}），圆形围栏为 NULL。
 */
@Component
public class V10GeofenceShapeMigration implements SchemaMigration {

    @Override
    public int version() {
        return 10;
    }

    @Override
    public String description() {
        return "geofence 增加 shape 与 points 支持多边形/矩形/路线围栏";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        addColumnIfMissing(jdbc, "geofence", "shape", "TEXT NOT NULL DEFAULT 'circle'");
        addColumnIfMissing(jdbc, "geofence", "points", "TEXT");
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
