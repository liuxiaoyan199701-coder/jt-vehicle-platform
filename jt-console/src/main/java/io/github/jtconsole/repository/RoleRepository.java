package io.github.jtconsole.repository;

import io.github.jtconsole.domain.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoleRepository {

    private static final String COLUMNS = """
            id, tenant_id, code, name, builtin, data_scope, remark, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public RoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Role> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM role WHERE id = ?")
                .param(id).query(RoleRepository::map).optional();
    }

    /** 平台内置角色按 code 查找（{@code tenant_id IS NULL}）。 */
    public Optional<Role> findBuiltin(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM role WHERE tenant_id IS NULL AND code = ?")
                .param(code).query(RoleRepository::map).optional();
    }

    /** 某租户可用的角色：平台内置模板 + 该租户自定义角色。平台管理员模板不对租户开放。 */
    public List<Role> findAvailableFor(Long tenantId) {
        if (tenantId == null) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM role WHERE tenant_id IS NULL ORDER BY id")
                    .query(RoleRepository::map).list();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM role
                        WHERE (tenant_id IS NULL AND code <> ?) OR tenant_id = ?
                        ORDER BY tenant_id IS NOT NULL, id
                        """)
                .param(Role.PLATFORM_ADMIN).param(tenantId)
                .query(RoleRepository::map).list();
    }

    public List<Role> findByTenant(Long tenantId) {
        if (tenantId == null) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM role WHERE tenant_id IS NULL ORDER BY id")
                    .query(RoleRepository::map).list();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM role WHERE tenant_id = ? ORDER BY id")
                .param(tenantId).query(RoleRepository::map).list();
    }

    /** 全部租户自定义角色，供平台管理员不指定租户时浏览。 */
    public List<Role> findAllTenantRoles() {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM role WHERE tenant_id IS NOT NULL ORDER BY tenant_id, id
                        """)
                .query(RoleRepository::map).list();
    }

    public List<Role> findByAccount(long accountId) {
        return jdbc.sql("""
                        SELECT r.id, r.tenant_id, r.code, r.name, r.builtin, r.data_scope,
                               r.remark, r.created_at, r.updated_at
                        FROM account_role ar
                        JOIN role r ON r.id = ar.role_id
                        WHERE ar.account_id = ?
                        ORDER BY r.id
                        """)
                .param(accountId).query(RoleRepository::map).list();
    }

    private static Role map(ResultSet rs, int rowNum) throws SQLException {
        // wasNull() 只反映最近一次取值，必须紧接着读取判断，不能放进构造器实参里。
        long rawTenantId = rs.getLong("tenant_id");
        Long tenantId = rs.wasNull() ? null : rawTenantId;
        return new Role(
                rs.getLong("id"),
                tenantId,
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("builtin") == 1,
                rs.getString("data_scope"),
                rs.getString("remark"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    public boolean codeExists(Long tenantId, String code, Long excludedId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM role WHERE COALESCE(tenant_id, 0) = ? AND code = ?");
        if (excludedId != null) {
            sql.append(" AND id <> ?");
        }
        var spec = jdbc.sql(sql.toString())
                .param(tenantId == null ? 0L : tenantId)
                .param(code);
        if (excludedId != null) {
            spec = spec.param(excludedId);
        }
        Integer count = spec.query(Integer.class).single();
        return count != null && count > 0;
    }

    public long insert(Role role) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO role (tenant_id, code, name, builtin, data_scope, remark,
                                          created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(role.tenantId()).param(role.code()).param(role.name())
                .param(role.builtin() ? 1 : 0).param(role.dataScope()).param(role.remark())
                .param(role.createdAt()).param(role.updatedAt())
                .update(key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("创建角色后未返回主键");
        }
        return value.longValue();
    }

    public int update(Role role) {
        return jdbc.sql("""
                        UPDATE role SET name = ?, data_scope = ?, remark = ?, updated_at = ?
                        WHERE id = ?
                        """)
                .param(role.name()).param(role.dataScope()).param(role.remark())
                .param(Instant.now().toString()).param(role.id())
                .update();
    }

    @Transactional
    public int delete(long roleId) {
        jdbc.sql("DELETE FROM role_permission WHERE role_id = ?").param(roleId).update();
        jdbc.sql("DELETE FROM role_dept WHERE role_id = ?").param(roleId).update();
        return jdbc.sql("DELETE FROM role WHERE id = ? AND builtin = 0").param(roleId).update();
    }

    public int countAccounts(long roleId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM account_role WHERE role_id = ?")
                .param(roleId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public List<String> findPermissions(long roleId) {
        return jdbc.sql("""
                        SELECT permission_code FROM role_permission
                        WHERE role_id = ? ORDER BY permission_code
                        """)
                .param(roleId).query(String.class).list();
    }

    @Transactional
    public void replacePermissions(long roleId, Collection<String> codes) {
        jdbc.sql("DELETE FROM role_permission WHERE role_id = ?").param(roleId).update();
        for (String code : List.copyOf(codes)) {
            jdbc.sql("""
                            INSERT INTO role_permission (role_id, permission_code) VALUES (?, ?)
                            ON CONFLICT(role_id, permission_code) DO NOTHING
                            """)
                    .param(roleId).param(code).update();
        }
    }

    public List<Long> findDepartments(long roleId) {
        return jdbc.sql("SELECT department_id FROM role_dept WHERE role_id = ? ORDER BY department_id")
                .param(roleId).query(Long.class).list();
    }

    @Transactional
    public void replaceDepartments(long roleId, Collection<Long> departmentIds) {
        jdbc.sql("DELETE FROM role_dept WHERE role_id = ?").param(roleId).update();
        for (Long departmentId : List.copyOf(departmentIds)) {
            jdbc.sql("""
                            INSERT INTO role_dept (role_id, department_id) VALUES (?, ?)
                            ON CONFLICT(role_id, department_id) DO NOTHING
                            """)
                    .param(roleId).param(departmentId).update();
        }
    }

    /** 账号绑定的全部角色权限码并集。 */
    public List<String> findPermissionCodesForAccount(long accountId) {
        return jdbc.sql("""
                        SELECT DISTINCT rp.permission_code
                        FROM account_role ar
                        JOIN role_permission rp ON rp.role_id = ar.role_id
                        JOIN permission p ON p.code = rp.permission_code AND p.active = 1
                        WHERE ar.account_id = ?
                        """)
                .param(accountId).query(String.class).list();
    }

    /** 账号全部角色显式指定的自定义部门集合。 */
    public List<Long> findCustomDepartmentsForAccount(long accountId) {
        return jdbc.sql("""
                        SELECT DISTINCT rd.department_id
                        FROM account_role ar
                        JOIN role r ON r.id = ar.role_id
                        JOIN role_dept rd ON rd.role_id = r.id
                        WHERE ar.account_id = ? AND r.data_scope = 'CUSTOM'
                        """)
                .param(accountId).query(Long.class).list();
    }

    @Transactional
    public void replaceAccountRoles(long accountId, Collection<Long> roleIds) {
        jdbc.sql("DELETE FROM account_role WHERE account_id = ?").param(accountId).update();
        for (Long roleId : List.copyOf(roleIds)) {
            jdbc.sql("""
                            INSERT INTO account_role (account_id, role_id) VALUES (?, ?)
                            ON CONFLICT(account_id, role_id) DO NOTHING
                            """)
                    .param(accountId).param(roleId).update();
        }
    }

    public void deleteAccountRoles(long accountId) {
        jdbc.sql("DELETE FROM account_role WHERE account_id = ?").param(accountId).update();
    }

    /** 删除某租户下的全部自定义角色及其配置，用于租户清理。 */
    @Transactional
    public void deleteByTenant(long tenantId) {
        jdbc.sql("""
                        DELETE FROM role_permission
                        WHERE role_id IN (SELECT id FROM role WHERE tenant_id = ?)
                        """).param(tenantId).update();
        jdbc.sql("""
                        DELETE FROM role_dept
                        WHERE role_id IN (SELECT id FROM role WHERE tenant_id = ?)
                        """).param(tenantId).update();
        jdbc.sql("DELETE FROM role WHERE tenant_id = ?").param(tenantId).update();
    }
}
