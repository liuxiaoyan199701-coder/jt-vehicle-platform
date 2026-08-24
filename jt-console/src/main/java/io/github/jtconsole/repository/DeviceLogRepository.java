package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.security.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 设备日志的唯一存取口，页面与 AI 共用。
 *
 * <p><b>租户隔离在这里只做得到一半，剩下一半必须由调用方补齐</b>：日志库是独立文件，
 * 没有 {@code vehicle} 表可 join，{@link DataScope#deviceCondition} 那套「经车辆档案过滤」
 * 在这里无法执行。因此本仓储只按 {@code tenant_id} 收窄，**部门级收窄与设备可见性判定
 * 必须先在业务库上完成**——{@code DeviceLogQueryService.authorize} 是唯一的入口，
 * 它先用 {@code VehicleService.requireVisibleDevice} 判定，再进来取数。
 *
 * <p>换存储时（真到 ClickHouse 那天）只有本类需要重写，查询方不动。
 */
@Repository
public class DeviceLogRepository {

    private static final String COLUMNS = """
            id, event_id AS eventId, device_id AS deviceId, tenant_id AS tenantId,
            direction, msg_id AS msgId, serial_no AS serialNo, log_time AS logTime,
            summary, raw_hex AS rawHex, parsed_json AS parsedJson,
            decode_error AS decodeError, truncated, instance_id AS instanceId
            """;

    private final JdbcClient jdbc;

    public DeviceLogRepository(DeviceLogDatabase database) {
        this.jdbc = database.jdbc();
    }

    /** 幂等靠 {@code event_id} 唯一索引，重复投递静默吞掉。 */
    public boolean insertIgnore(DeviceLog log) {
        return jdbc.sql("""
                INSERT OR IGNORE INTO device_log
                    (event_id, device_id, tenant_id, direction, msg_id, serial_no, log_time,
                     summary, raw_hex, parsed_json, decode_error, truncated, instance_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(log.eventId()).param(log.deviceId()).param(log.tenantId())
                .param(log.direction()).param(log.msgId()).param(log.serialNo())
                .param(log.logTime()).param(log.summary()).param(log.rawHex())
                .param(log.parsedJson()).param(log.decodeError() ? 1 : 0)
                .param(log.truncated() ? 1 : 0).param(log.instanceId())
                .update() > 0;
    }

    public List<DeviceLog> findByDevice(DeviceLogFilter filter, DataScope scope) {
        if (scope.empty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        String where = where(filter, scope, params);
        params.add(filter.pageSize());
        params.add((filter.page() - 1) * filter.pageSize());
        return jdbc.sql("SELECT " + COLUMNS + " FROM device_log" + where
                        + " ORDER BY log_time DESC, id DESC LIMIT ? OFFSET ?")
                .params(params).query(DeviceLogRepository::map).list();
    }

    /** 按主键取单条。租户条款照旧只做得到 tenant_id 这一半，剩下一半由查询服务补齐。 */
    public Optional<DeviceLog> findById(long id, DataScope scope) {
        if (scope.empty()) {
            return Optional.empty();
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM device_log WHERE id = ?");
        List<Object> params = new ArrayList<>();
        params.add(id);
        if (scope.tenantId() != null) {
            sql.append(" AND tenant_id = ?");
            params.add(scope.tenantId());
        }
        return jdbc.sql(sql.toString()).params(params).query(DeviceLogRepository::map).optional();
    }

    public long count(DeviceLogFilter filter, DataScope scope) {
        if (scope.empty()) {
            return 0;
        }
        List<Object> params = new ArrayList<>();
        String where = where(filter, scope, params);
        Long total = jdbc.sql("SELECT COUNT(*) FROM device_log" + where)
                .params(params).query(Long.class).single();
        return total == null ? 0 : total;
    }

    /** 分批删除，单批一个事务：一条长事务会把日志库的写锁按住，采集端因此开始丢日志。 */
    public int deleteOlderThan(Instant cutoff, int batchSize) {
        return jdbc.sql("""
                DELETE FROM device_log WHERE id IN (
                    SELECT id FROM device_log WHERE log_time < ? ORDER BY id LIMIT ?
                )
                """).param(Timestamps.of(cutoff)).param(batchSize).update();
    }

    private static String where(DeviceLogFilter filter, DataScope scope, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE device_id = ?");
        params.add(filter.deviceId());
        // 平台管理员不带租户筛选时可见未建档设备（tenant_id 为 NULL）的日志。
        if (scope.tenantId() != null) {
            sql.append(" AND tenant_id = ?");
            params.add(scope.tenantId());
        }
        if (filter.direction() != null) {
            sql.append(" AND direction = ?");
            params.add(filter.direction());
        }
        if (filter.msgId() != null) {
            sql.append(" AND msg_id = ?");
            params.add(filter.msgId());
        }
        if (filter.keyword() != null) {
            // 只搜概要与解析结果；原始 hex 的语义检索不在本期范围。
            sql.append(" AND (summary LIKE ? OR parsed_json LIKE ?)");
            String like = '%' + filter.keyword() + '%';
            params.add(like);
            params.add(like);
        }
        sql.append(" AND log_time >= ? AND log_time <= ?");
        params.add(lower(filter.start()));
        params.add(upper(filter.end()));
        return sql.toString();
    }

    private static String lower(String value) {
        String normalized = TimeBounds.lower(value);
        return normalized == null ? "0000-01-01T00:00:00.000+08:00" : normalized;
    }

    private static String upper(String value) {
        String normalized = TimeBounds.upper(value);
        return normalized == null ? "9999-12-31T23:59:59.999+08:00" : normalized;
    }

    private static DeviceLog map(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceLog(
                rs.getLong("id"), rs.getString("eventId"), rs.getString("deviceId"),
                RowValues.nullableLong(rs, "tenantId"), rs.getString("direction"),
                RowValues.nullableInt(rs, "msgId"), RowValues.nullableInt(rs, "serialNo"),
                rs.getString("logTime"), rs.getString("summary"), rs.getString("rawHex"),
                rs.getString("parsedJson"), RowValues.flag(rs, "decodeError"),
                RowValues.flag(rs, "truncated"), rs.getString("instanceId"));
    }

    /**
     * 查询条件。{@code page} 从 1 起，与告警页多数派一致。
     *
     * @param direction 为空表示三个方向都要
     * @param msgId     十进制的 808 消息 ID；调用方负责把 {@code 0x0200} 这类写法解析进来
     */
    public record DeviceLogFilter(
            String deviceId, String start, String end, String direction,
            Integer msgId, String keyword, int page, int pageSize) {

        public DeviceLogFilter {
            if (deviceId == null || deviceId.isBlank()) {
                throw new IllegalArgumentException("设备号不能为空");
            }
            deviceId = deviceId.trim();
            direction = blankToNull(direction);
            keyword = blankToNull(keyword);
            if (page < 1) {
                throw new IllegalArgumentException("页码从 1 起");
            }
            if (pageSize < 1) {
                throw new IllegalArgumentException("每页条数必须为正");
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
