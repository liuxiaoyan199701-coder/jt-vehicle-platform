package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.domain.TenantStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TenantRepository {

    private static final String COLUMNS = """
            id, code, name, status, plan_id, expires_at, contact_name, contact_phone,
            remark, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tenant> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE id = ?")
                .param(id).query(TenantRepository::map).optional();
    }

    public Optional<Tenant> findByCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE code = ?")
                .param(code).query(TenantRepository::map).optional();
    }

    public List<Tenant> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant ORDER BY id")
                .query(TenantRepository::map).list();
    }

    /** 未过期判定要用到 expires_at，因此扫描活跃租户时连同 status 一起返回原始行。 */
    public List<Tenant> findByStatus(TenantStatus status) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE status = ? ORDER BY id")
                .param(status.name()).query(TenantRepository::map).list();
    }

    public boolean codeExists(String code, Long excludedId) {
        String sql = excludedId == null
                ? "SELECT COUNT(*) FROM tenant WHERE code = ?"
                : "SELECT COUNT(*) FROM tenant WHERE code = ? AND id <> ?";
        var spec = jdbc.sql(sql).param(code);
        if (excludedId != null) {
            spec = spec.param(excludedId);
        }
        Integer count = spec.query(Integer.class).single();
        return count != null && count > 0;
    }

    public boolean nameExists(String name) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM tenant WHERE name = ?")
                .param(name).query(Integer.class).single();
        return count != null && count > 0;
    }

    public long insert(Tenant tenant) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO tenant (code, name, status, plan_id, expires_at,
                                            contact_name, contact_phone, remark,
                                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(tenant.code()).param(tenant.name()).param(tenant.status())
                .param(tenant.planId()).param(tenant.expiresAt())
                .param(tenant.contactName()).param(tenant.contactPhone()).param(tenant.remark())
                .param(tenant.createdAt()).param(tenant.updatedAt())
                .update(key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("创建租户后未返回主键");
        }
        return value.longValue();
    }

    public int update(Tenant tenant) {
        return jdbc.sql("""
                        UPDATE tenant SET code = ?, name = ?, plan_id = ?, expires_at = ?,
                            contact_name = ?, contact_phone = ?, remark = ?, updated_at = ?
                        WHERE id = ?
                        """)
                .param(tenant.code()).param(tenant.name()).param(tenant.planId())
                .param(tenant.expiresAt()).param(tenant.contactName())
                .param(tenant.contactPhone()).param(tenant.remark())
                .param(Timestamps.now()).param(tenant.id())
                .update();
    }

    public int updateStatus(long tenantId, TenantStatus status) {
        return jdbc.sql("UPDATE tenant SET status = ?, updated_at = ? WHERE id = ?")
                .param(status.name()).param(Timestamps.now()).param(tenantId)
                .update();
    }

    public int updateExpiry(long tenantId, Long planId, String expiresAt) {
        return jdbc.sql("UPDATE tenant SET plan_id = ?, expires_at = ?, updated_at = ? WHERE id = ?")
                .param(planId).param(expiresAt).param(Timestamps.now()).param(tenantId)
                .update();
    }

    public int delete(long tenantId) {
        return jdbc.sql("DELETE FROM tenant WHERE id = ?").param(tenantId).update();
    }

    public int countVehicles(long tenantId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM vehicle WHERE tenant_id = ?")
                .param(tenantId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public int countAccounts(long tenantId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM account WHERE tenant_id = ?")
                .param(tenantId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    /** 某租户已建档的全部设备标识，用于停用时批量断连。 */
    public List<String> findDeviceIds(long tenantId) {
        return jdbc.sql("SELECT device_id FROM vehicle WHERE tenant_id = ? ORDER BY device_id")
                .param(tenantId).query(String.class).list();
    }

    private static Tenant map(ResultSet rs, int rowNum) throws SQLException {
        return new Tenant(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("status"),
                RowValues.nullableLong(rs, "plan_id"),
                rs.getString("expires_at"),
                rs.getString("contact_name"),
                rs.getString("contact_phone"),
                rs.getString("remark"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
