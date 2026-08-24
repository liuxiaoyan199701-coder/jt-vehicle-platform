package io.github.jtconsole.repository;

import io.github.jtconsole.domain.TerminalPage;
import io.github.jtconsole.domain.TerminalSummary;
import io.github.jtconsole.security.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 终端清单查询：台账左连档案与实时状态。
 *
 * <p><b>连接方式决定了可见范围，这是本类唯一需要小心的地方</b>：
 * 未建档终端没有租户归属，让租户看到等于泄露其他租户或施工方的设备。
 * 所以平台范围用 {@code LEFT JOIN vehicle}（看得到未建档的），
 * 租户范围用 {@code INNER JOIN vehicle} 并拼 {@code scope.vehicleCondition}
 * （只看得到本租户已建档的，与他们在车辆档案里能看到的集合完全一致）。
 */
@Repository
public class TerminalQueryRepository {

    private static final String COLUMNS = """
            SELECT t.device_id AS deviceId, t.terminal_id AS terminalId, t.maker_id AS makerId,
                   t.device_model AS deviceModel, t.province_id AS provinceId, t.city_id AS cityId,
                   t.reported_plate AS reportedPlate, t.reported_color AS reportedColor,
                   t.protocol_version AS protocolVersion, t.first_seen_at AS firstSeenAt,
                   t.last_seen_at AS lastSeenAt, t.last_result AS lastResult,
                   v.device_id AS archivedDeviceId, v.plate_no AS plateNo,
                   v.tenant_id AS tenantId,
                   s.online AS online, s.last_seen_at AS onlineSeenAt
            """;

    private final JdbcClient jdbc;

    public TerminalQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TerminalPage search(TerminalFilter filter, DataScope scope) {
        if (scope.empty()) {
            return new TerminalPage(List.of(), 0, filter.page(), filter.pageSize());
        }
        List<Object> params = new ArrayList<>();
        String from = from(scope) + where(filter, scope, params);

        Long total = jdbc.sql("SELECT COUNT(*)" + from).params(params).query(Long.class).single();
        if (total == null || total == 0) {
            return new TerminalPage(List.of(), 0, filter.page(), filter.pageSize());
        }
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(filter.pageSize());
        pageParams.add((filter.page() - 1) * filter.pageSize());
        List<TerminalSummary> items = jdbc.sql(COLUMNS + from
                        + " ORDER BY t.last_seen_at DESC, t.device_id DESC LIMIT ? OFFSET ?")
                .params(pageParams).query(TerminalQueryRepository::map).list();
        return new TerminalPage(items, total, filter.page(), filter.pageSize());
    }

    /** 租户范围下 INNER JOIN 是硬边界：没有档案的终端对租户根本不该存在。 */
    private static String from(DataScope scope) {
        String vehicleJoin = scope.isPlatform() ? "LEFT JOIN" : "INNER JOIN";
        return " FROM terminal t "
                + vehicleJoin + " vehicle v ON v.device_id = t.device_id "
                + "LEFT JOIN device_status s ON s.device_id = t.device_id";
    }

    private static String where(TerminalFilter filter, DataScope scope, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (scope.isPlatform()) {
            if (scope.tenantId() != null) {
                sql.append(" AND v.tenant_id = ?");
                params.add(scope.tenantId());
            }
        } else {
            sql.append(scope.vehicleCondition("v"));
            params.addAll(scope.parameters());
        }
        if (filter.archived() != null) {
            sql.append(filter.archived() ? " AND v.device_id IS NOT NULL" : " AND v.device_id IS NULL");
        }
        if (filter.online() != null) {
            sql.append(filter.online()
                    ? " AND COALESCE(s.online, 0) = 1"
                    : " AND COALESCE(s.online, 0) = 0");
        }
        if (filter.keyword() != null) {
            sql.append(" AND (t.device_id LIKE ? OR t.terminal_id LIKE ? OR t.reported_plate LIKE ?"
                    + " OR t.device_model LIKE ? OR v.plate_no LIKE ?)");
            String like = '%' + filter.keyword() + '%';
            for (int index = 0; index < 5; index++) {
                params.add(like);
            }
        }
        if (filter.start() != null) {
            sql.append(" AND t.last_seen_at >= ?");
            params.add(TimeBounds.lower(filter.start()));
        }
        if (filter.end() != null) {
            sql.append(" AND t.last_seen_at <= ?");
            params.add(TimeBounds.upper(filter.end()));
        }
        return sql.toString();
    }

    private static TerminalSummary map(ResultSet rs, int rowNum) throws SQLException {
        // 建档与否只看档案行在不在，不看车牌或租户是否为空——那两列的可空性是另一回事。
        boolean archived = rs.getString("archivedDeviceId") != null;
        return new TerminalSummary(
                rs.getString("deviceId"), rs.getString("terminalId"), rs.getString("makerId"),
                rs.getString("deviceModel"), RowValues.nullableInt(rs, "provinceId"),
                RowValues.nullableInt(rs, "cityId"), rs.getString("reportedPlate"),
                RowValues.nullableInt(rs, "reportedColor"), rs.getString("protocolVersion"),
                rs.getString("firstSeenAt"), rs.getString("lastSeenAt"), rs.getString("lastResult"),
                archived, rs.getString("plateNo"), RowValues.nullableLong(rs, "tenantId"),
                rs.getInt("online") == 1, rs.getString("onlineSeenAt"));
    }

    /**
     * @param archived 为空表示不按建档状态筛选
     * @param online   为空表示不按在线状态筛选
     * @param start    最近见到时间的下界；语义是「最近一次注册/鉴权」，不是最近在线
     */
    public record TerminalFilter(
            String keyword, Boolean archived, Boolean online,
            String start, String end, int page, int pageSize) {

        public TerminalFilter {
            keyword = blankToNull(keyword);
            start = blankToNull(start);
            end = blankToNull(end);
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
