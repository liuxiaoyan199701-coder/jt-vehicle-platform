package io.github.jtconsole.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v3：给 {@code track_point} 建立 {@code (device_id, device_time)} 唯一约束，建立前先去重。
 *
 * <p>补传（0x0704 盲区补报）的时间窗常与已经实时上报过的点重叠。信封级幂等只能挡住同一
 * {@code eventId} 的重投，挡不住「同一个点先以 0x0200 上报、后又出现在补传批次里」——那会产生
 * 重复轨迹并重复累加里程。唯一可靠的拦截位置是数据库约束：先查后写在 SQLite 的并发投递下仍有竞态，
 * 而且是纯粹的额外开销。
 *
 * <p>{@code device_time} 可空，SQLite 的唯一约束视 NULL 互不相等，因此没有设备时间的轨迹点不受
 * 约束影响。这是有意的：没有设备时间就无从判断两条记录是不是同一个点。
 */
@Component
public class V3TrackPointUniquenessMigration implements SchemaMigration {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(V3TrackPointUniquenessMigration.class);

    @Override
    public int version() {
        return 3;
    }

    @Override
    public String description() {
        return "去重 track_point 并建立 (device_id, device_time) 唯一约束";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        // 每组只保留 id 最小的一条：先到的那条与当时的日统计、告警时间线一致，留它最省事。
        int removed = jdbc.sql("""
                        DELETE FROM track_point
                        WHERE device_time IS NOT NULL
                          AND id NOT IN (
                              SELECT MIN(id) FROM track_point
                              WHERE device_time IS NOT NULL
                              GROUP BY device_id, device_time
                          )
                        """)
                .update();
        if (removed > 0) {
            LOGGER.info("建立唯一约束前清理了 {} 条重复轨迹点", removed);
        }
        jdbc.sql("""
                        CREATE UNIQUE INDEX IF NOT EXISTS ux_track_device_time
                            ON track_point (device_id, device_time)
                        """)
                .update();
    }
}
