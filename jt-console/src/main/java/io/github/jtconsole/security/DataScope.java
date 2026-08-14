package io.github.jtconsole.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次请求的可见数据范围，是租户与部门隔离的唯一强制载体。
 *
 * <p>刻意不做 AOP 隐式 SQL 改写：隐式魔法一处失效即静默越权且无法在评审中看见。
 * 仓储方法把本类作为必选参数接收，并把 {@link #vehicleCondition} /
 * {@link #deviceCondition} 拼进 SQL，隔离性因此在代码与测试中都是可见的。
 *
 * <p>租户是硬边界，任何角色配置都不能放宽；部门只在租户内部再收窄。
 * 未分配部门的车辆只对 {@link #tenantWide} 可见——{@code department_id IN (...)}
 * 天然排除 NULL，宁可少看到，不可越权看到。
 */
public final class DataScope {

    private static final DataScope PLATFORM_ALL = new DataScope(true, null, null);

    private final boolean platform;
    private final Long tenantId;
    /** {@code null} 表示不限部门；空集合表示「什么都看不到」。 */
    private final Set<Long> departmentIds;

    private DataScope(boolean platform, Long tenantId, Set<Long> departmentIds) {
        this.platform = platform;
        this.tenantId = tenantId;
        this.departmentIds = departmentIds == null
                ? null
                : Set.copyOf(new LinkedHashSet<>(departmentIds));
    }

    /** 平台管理员：跨租户可见，含未建档设备。 */
    public static DataScope platform() {
        return PLATFORM_ALL;
    }

    /** 平台管理员按租户筛选。{@code tenantId} 为空时退化为跨租户。 */
    public static DataScope platformFilteredBy(Long tenantId) {
        return tenantId == null ? PLATFORM_ALL : new DataScope(true, tenantId, null);
    }

    /** 租户全量范围。 */
    public static DataScope tenantWide(long tenantId) {
        return new DataScope(false, tenantId, null);
    }

    /** 租户内限定部门集合；传入空集合表示该账号看不到任何车辆。 */
    public static DataScope departments(long tenantId, Set<Long> departmentIds) {
        return new DataScope(false, tenantId, Objects.requireNonNull(departmentIds, "departmentIds"));
    }

    public boolean isPlatform() {
        return platform;
    }

    /** 生效的租户过滤值；平台管理员未指定筛选时为空。 */
    public Long tenantId() {
        return tenantId;
    }

    /**
     * 归属写入时使用的租户。平台管理员必须显式指定目标租户，因此调用方需自行处理为空的情况。
     */
    public boolean restrictedToTenant() {
        return tenantId != null;
    }

    public boolean departmentRestricted() {
        return departmentIds != null;
    }

    public Set<Long> visibleDepartmentIds() {
        return departmentIds == null ? Set.of() : departmentIds;
    }

    /** 该范围是否注定查不到任何车辆（部门集合为空）。 */
    public boolean empty() {
        return departmentIds != null && departmentIds.isEmpty();
    }

    /**
     * 作用在车辆表别名上的过滤条件，返回以 {@code AND} 开头的片段或空串。
     *
     * @param alias 车辆表别名，例如 {@code v}；传空串表示不加前缀
     */
    public String vehicleCondition(String alias) {
        String prefix = alias == null || alias.isBlank() ? "" : alias.trim() + ".";
        if (empty()) {
            return " AND 1 = 0";
        }
        if (tenantId == null) {
            return "";
        }
        StringBuilder sql = new StringBuilder(" AND ").append(prefix).append("tenant_id = ?");
        if (departmentIds != null) {
            sql.append(" AND ").append(prefix).append("department_id IN (")
                    .append(placeholders(departmentIds.size())).append(')');
        }
        return sql.toString();
    }

    /**
     * 作用在「按 deviceId 聚合」的表上的过滤条件：轨迹、状态、告警、日统计、多媒体等
     * 都不带 tenant_id 列，一律经车辆档案这张唯一权威映射过滤。
     *
     * @param deviceColumn 目标表的设备标识列，例如 {@code t.device_id}
     */
    public String deviceCondition(String deviceColumn) {
        if (empty()) {
            return " AND 1 = 0";
        }
        if (tenantId == null) {
            return "";
        }
        StringBuilder sql = new StringBuilder(" AND ").append(deviceColumn)
                .append(" IN (SELECT device_id FROM vehicle WHERE tenant_id = ?");
        if (departmentIds != null) {
            sql.append(" AND department_id IN (").append(placeholders(departmentIds.size())).append(')');
        }
        return sql.append(')').toString();
    }

    /**
     * 只按租户过滤的条件，用于车队、围栏、部门、岗位、角色、账号这类租户级实体。
     * 它们是组织元数据而非车辆数据，不再按部门收窄。
     */
    public String tenantCondition(String alias) {
        String prefix = alias == null || alias.isBlank() ? "" : alias.trim() + ".";
        return tenantId == null ? "" : " AND " + prefix + "tenant_id = ?";
    }

    /** {@link #vehicleCondition} 与 {@link #deviceCondition} 对应的绑定参数，顺序一致。 */
    public List<Object> parameters() {
        if (empty() || tenantId == null) {
            return List.of();
        }
        if (departmentIds == null) {
            return List.of(tenantId);
        }
        List<Object> params = new java.util.ArrayList<>(departmentIds.size() + 1);
        params.add(tenantId);
        params.addAll(departmentIds);
        return List.copyOf(params);
    }

    /** {@link #tenantCondition} 对应的绑定参数。 */
    public List<Object> tenantParameters() {
        return tenantId == null ? List.of() : List.of(tenantId);
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    @Override
    public String toString() {
        if (platform && tenantId == null) {
            return "DataScope[platform]";
        }
        return "DataScope[tenant=" + tenantId
                + (departmentIds == null ? ", 全部部门" : ", 部门数=" + departmentIds.size()) + "]";
    }
}
