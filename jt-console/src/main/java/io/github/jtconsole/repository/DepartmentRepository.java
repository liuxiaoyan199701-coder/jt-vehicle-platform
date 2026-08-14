package io.github.jtconsole.repository;

import io.github.jtconsole.domain.Department;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class DepartmentRepository {

    private static final String COLUMNS = """
            id, tenant_id, parent_id, name, sort_order, enabled, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public DepartmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Department> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM department WHERE id = ?")
                .param(id).query(DepartmentRepository::map).optional();
    }

    public List<Department> findByTenant(long tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM department WHERE tenant_id = ?
                        ORDER BY sort_order, id
                        """)
                .param(tenantId).query(DepartmentRepository::map).list();
    }

    /**
     * 部门及其全部子孙的标识集合。
     *
     * <p>在内存里按父子关系展开而不是用递归 CTE：部门树规模是「一个租户的组织架构」，
     * 一次全量读比递归查询更简单，也让防环检查复用同一份数据。
     */
    public Set<Long> findSubtreeIds(long tenantId, long rootId) {
        Map<Long, List<Long>> childrenByParent = new LinkedHashMap<>();
        for (Department department : findByTenant(tenantId)) {
            childrenByParent
                    .computeIfAbsent(department.parentId(), key -> new ArrayList<>())
                    .add(department.id());
        }
        Set<Long> collected = new LinkedHashSet<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.push(rootId);
        while (!pending.isEmpty()) {
            Long current = pending.pop();
            if (!collected.add(current)) {
                continue;
            }
            for (Long child : childrenByParent.getOrDefault(current, List.of())) {
                pending.push(child);
            }
        }
        return collected;
    }

    /** 多个部门的子树并集，供 {@code DEPT_AND_CHILDREN} 数据范围使用。 */
    public Set<Long> findSubtreeIds(long tenantId, Set<Long> rootIds) {
        if (rootIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, List<Long>> childrenByParent = new LinkedHashMap<>();
        for (Department department : findByTenant(tenantId)) {
            childrenByParent
                    .computeIfAbsent(department.parentId(), key -> new ArrayList<>())
                    .add(department.id());
        }
        Set<Long> collected = new LinkedHashSet<>();
        Deque<Long> pending = new ArrayDeque<>(rootIds);
        Set<Long> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Long current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            collected.add(current);
            for (Long child : childrenByParent.getOrDefault(current, List.of())) {
                pending.push(child);
            }
        }
        return collected;
    }

    public boolean nameExistsUnderParent(long tenantId, Long parentId, String name, Long excludedId) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM department
                WHERE tenant_id = ? AND COALESCE(parent_id, 0) = ? AND name = ?
                """);
        List<Object> params = new ArrayList<>(List.of(
                tenantId, parentId == null ? 0L : parentId, name));
        if (excludedId != null) {
            sql.append(" AND id <> ?");
            params.add(excludedId);
        }
        Integer count = jdbc.sql(sql.toString()).params(params).query(Integer.class).single();
        return count != null && count > 0;
    }

    public long insert(Department department) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO department (tenant_id, parent_id, name, sort_order, enabled,
                                                created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(department.tenantId()).param(department.parentId()).param(department.name())
                .param(department.sortOrder()).param(department.enabled() ? 1 : 0)
                .param(department.createdAt()).param(department.updatedAt())
                .update(key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("创建部门后未返回主键");
        }
        return value.longValue();
    }

    public int update(long id, Long parentId, String name, int sortOrder, boolean enabled) {
        return jdbc.sql("""
                        UPDATE department
                        SET parent_id = ?, name = ?, sort_order = ?, enabled = ?, updated_at = ?
                        WHERE id = ?
                        """)
                .param(parentId).param(name).param(sortOrder).param(enabled ? 1 : 0)
                .param(Instant.now().toString()).param(id)
                .update();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM department WHERE id = ?").param(id).update();
    }

    public int countChildren(long id) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM department WHERE parent_id = ?")
                .param(id).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public int countVehicles(long departmentId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM vehicle WHERE department_id = ?")
                .param(departmentId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    /** 按部门统计账号数与车辆数，供部门树一次性带出非空提示。 */
    public Map<Long, int[]> countUsage(long tenantId) {
        Map<Long, int[]> usage = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT department_id, COUNT(*) AS total
                        FROM account WHERE tenant_id = ? AND department_id IS NOT NULL
                        GROUP BY department_id
                        """)
                .param(tenantId)
                .query((rs, rowNum) -> {
                    usage.computeIfAbsent(rs.getLong("department_id"), key -> new int[2])[0] =
                            rs.getInt("total");
                    return null;
                })
                .list();
        jdbc.sql("""
                        SELECT department_id, COUNT(*) AS total
                        FROM vehicle WHERE tenant_id = ? AND department_id IS NOT NULL
                        GROUP BY department_id
                        """)
                .param(tenantId)
                .query((rs, rowNum) -> {
                    usage.computeIfAbsent(rs.getLong("department_id"), key -> new int[2])[1] =
                            rs.getInt("total");
                    return null;
                })
                .list();
        return usage;
    }

    public void deleteByTenant(long tenantId) {
        jdbc.sql("DELETE FROM department WHERE tenant_id = ?").param(tenantId).update();
    }

    private static Department map(ResultSet rs, int rowNum) throws SQLException {
        return new Department(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                RowValues.nullableLong(rs, "parent_id"),
                rs.getString("name"),
                rs.getInt("sort_order"),
                RowValues.flag(rs, "enabled"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
