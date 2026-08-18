package io.github.jtconsole.repository;

import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.security.DataScope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MediaRepository {

    /** 列清单在三处查询间共用，漏掉一列的后果是那一列静默为 null。 */
    private static final String COLUMNS = """
            id, device_id AS deviceId, file_id AS fileId, file_type AS fileType,
            file_format AS fileFormat, file_name AS fileName, size,
            access_address AS accessAddress, channel_id AS channelId,
            event_code AS eventCode, lat, lng, gcj_lat AS gcjLat, gcj_lng AS gcjLng,
            captured_at AS capturedAt
            """;

    private final JdbcClient jdbc;

    public MediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等插入：同一 (device_id, file_id) 的重复投递不产生新行，也不报错。
     */
    public void insertIgnore(MediaFile file) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO media_file
                            (device_id, file_id, file_type, file_format, file_name, size,
                             access_address, channel_id, event_code,
                             lat, lng, gcj_lat, gcj_lng, captured_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(file.deviceId())
                .param(file.fileId())
                .param(file.fileType())
                .param(file.fileFormat())
                .param(file.fileName())
                .param(file.size())
                .param(file.accessAddress())
                .param(file.channelId())
                .param(file.eventCode())
                .param(file.lat())
                .param(file.lng())
                .param(file.gcjLat())
                .param(file.gcjLng())
                .param(file.capturedAt())
                .update();
    }

    /**
     * 某设备最近上传的多媒体文件，最新的在前。监控页指令面板用它，签名保持不变。
     */
    public List<MediaFile> findRecentByDevice(String deviceId, int limit, DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(deviceId);
        params.addAll(scope.parameters());
        params.add(limit);
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM media_file
                        WHERE device_id = ?
                        """ + scope.deviceCondition("device_id") + """
                        ORDER BY captured_at DESC, id DESC
                        LIMIT ?
                        """)
                .params(params)
                .query(MediaFile.class)
                .list();
    }

    /**
     * 多条件检索。所有筛选项皆可选，唯独 {@link DataScope} 是强制的。
     */
    public MediaPage search(MediaFilter filter, DataScope scope) {
        if (scope.empty()) {
            return new MediaPage(List.of(), 0, filter.page(), filter.pageSize());
        }
        Where where = where(filter, scope);
        Integer total = jdbc.sql("SELECT COUNT(*) FROM media_file" + where.sql())
                .params(where.params())
                .query(Integer.class)
                .single();
        if (total == null || total == 0) {
            return new MediaPage(List.of(), 0, filter.page(), filter.pageSize());
        }
        int offset = (filter.page() - 1) * filter.pageSize();
        List<Object> params = new ArrayList<>(where.params());
        params.add(filter.pageSize());
        params.add(offset);
        List<MediaFile> items = jdbc.sql("SELECT " + COLUMNS + " FROM media_file" + where.sql()
                        + " ORDER BY captured_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(params)
                .query(MediaFile.class)
                .list();
        return new MediaPage(items, total, filter.page(), filter.pageSize());
    }

    /**
     * 某设备在一个时间窗内的抓拍。
     *
     * <p>供「查看该告警时段的抓拍」使用。**刻意不叫 findByAlarm**：0x0801 只给了事件项编码
     * （0 平台指令 / 1 定时 / 2 抢劫 / 3 碰撞侧翻），没有任何字段能定位到具体那条告警，
     * 所以这里返回的是「这段时间该车拍的照片」，不是「这条告警的照片」。命名如实反映这一点，
     * 免得调用方把它当因果关系用。
     */
    public List<MediaFile> findByDeviceWindow(
            String deviceId, String from, String to, int limit, DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(deviceId);
        params.addAll(scope.parameters());
        params.add(TimeBounds.lower(from));
        params.add(TimeBounds.upper(to));
        params.add(limit);
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM media_file
                        WHERE device_id = ?
                        """ + scope.deviceCondition("device_id") + """
                         AND captured_at >= ? AND captured_at <= ?
                        ORDER BY captured_at DESC, id DESC
                        LIMIT ?
                        """)
                .params(params)
                .query(MediaFile.class)
                .list();
    }

    private static Where where(MediaFilter filter, DataScope scope) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        // 范围条件排在最前：它是强制项，其余都是用户可选过滤。
        sql.append(scope.deviceCondition("device_id"));
        params.addAll(scope.parameters());
        add(sql, params, "device_id = ?", filter.deviceId());
        add(sql, params, "file_type = ?", filter.fileType());
        // 与告警、轨迹查询同一个坑：captured_at 是字符串，边界写成空格分隔会一条都查不出来。
        add(sql, params, "captured_at >= ?", TimeBounds.lower(filter.start()));
        add(sql, params, "captured_at <= ?", TimeBounds.upper(filter.end()));
        if (filter.channelId() != null) {
            sql.append(" AND channel_id = ?");
            params.add(filter.channelId());
        }
        // 事件项编码：0 平台下发、1 定时，>=2 为报警触发。前端只给「手动 / 告警」两档。
        if (filter.trigger() != null) {
            switch (filter.trigger()) {
                case ALARM -> sql.append(" AND event_code >= 2");
                // 历史行（v7 之前）event_code 可能为空，按「非告警」归入手动，不凭空归到告警去。
                case MANUAL -> sql.append(" AND (event_code IS NULL OR event_code < 2)");
            }
        }
        if (Boolean.TRUE.equals(filter.locatedOnly())) {
            sql.append(" AND gcj_lat IS NOT NULL AND gcj_lng IS NOT NULL");
        }
        return new Where(sql.toString(), params);
    }

    private static void add(StringBuilder sql, List<Object> params, String clause, Object value) {
        String actual = value == null ? null : value.toString().trim();
        if (actual != null && !actual.isBlank()) {
            sql.append(" AND ").append(clause);
            params.add(actual);
        }
    }

    /**
     * 抓拍的触发来源。
     *
     * <p>只有两档而不是把 0x0801 的四个事件项编码原样透出：用户关心的是「这张是人点的还是车自己
     * 触发的」，而「抢劫」与「碰撞侧翻」的区分在告警那边已经有了，重复暴露只会让筛选器变长。
     */
    public enum MediaTrigger {
        /** 平台下发指令或定时动作（event_code 为 0、1 或缺失）。 */
        MANUAL,
        /** 报警触发（event_code >= 2）。 */
        ALARM
    }

    public record MediaFilter(
            String deviceId, String fileType, Integer channelId, MediaTrigger trigger,
            Boolean locatedOnly, String start, String end, int page, int pageSize) {}

    public record MediaPage(List<MediaFile> items, int total, int page, int pageSize) {}

    private record Where(String sql, List<Object> params) {
    }
}
