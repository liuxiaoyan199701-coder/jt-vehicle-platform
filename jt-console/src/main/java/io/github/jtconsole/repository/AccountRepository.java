package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Account;
import io.github.jtconsole.domain.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountRepository {

    private static final String COLUMNS = """
            id, username, password_hash, display_name, tenant_id, department_id, position_id,
            status, last_login_at, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEmpty() {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM account").query(Integer.class).single();
        return count == null || count == 0;
    }

    public Optional<Account> findByUsername(String username) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE username = ?")
                .param(username).query(AccountRepository::map).optional();
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE id = ?")
                .param(id).query(AccountRepository::map).optional();
    }

    public boolean usernameExists(String username) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM account WHERE username = ?")
                .param(username).query(Integer.class).single();
        return count != null && count > 0;
    }

    /**
     * 按租户与关键词检索账号。{@code tenantId} 为空表示不限租户（平台管理员视角）。
     */
    public List<Account> search(Long tenantId, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM account WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (tenantId != null) {
            sql.append(" AND tenant_id = ?");
            params.add(tenantId);
        }
        String trimmed = keyword == null ? "" : keyword.trim();
        if (!trimmed.isEmpty()) {
            sql.append(" AND (username LIKE ? ESCAPE '\\' OR COALESCE(display_name, '') LIKE ? ESCAPE '\\')");
            String pattern = "%" + escapeLike(trimmed) + "%";
            params.add(pattern);
            params.add(pattern);
        }
        sql.append(" ORDER BY tenant_id IS NOT NULL, tenant_id, username COLLATE NOCASE");
        return jdbc.sql(sql.toString()).params(params).query(AccountRepository::map).list();
    }

    public List<Account> findByTenant(long tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE tenant_id = ? ORDER BY id")
                .param(tenantId).query(AccountRepository::map).list();
    }

    public List<Long> findIdsByTenant(long tenantId) {
        return jdbc.sql("SELECT id FROM account WHERE tenant_id = ? ORDER BY id")
                .param(tenantId).query(Long.class).list();
    }

    public long insert(Account account) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO account (username, password_hash, display_name, tenant_id,
                                             department_id, position_id, status,
                                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(account.username()).param(account.passwordHash())
                .param(account.displayName()).param(account.tenantId())
                .param(account.departmentId()).param(account.positionId())
                .param(account.status())
                .param(account.createdAt()).param(account.updatedAt())
                .update(key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("创建账号后未返回主键");
        }
        return value.longValue();
    }

    public int updateProfile(long id, String displayName, Long departmentId, Long positionId) {
        return jdbc.sql("""
                        UPDATE account SET display_name = ?, department_id = ?, position_id = ?,
                            updated_at = ?
                        WHERE id = ?
                        """)
                .param(displayName).param(departmentId).param(positionId)
                .param(Timestamps.now()).param(id)
                .update();
    }

    public int updatePasswordHash(long id, String passwordHash) {
        return jdbc.sql("UPDATE account SET password_hash = ?, updated_at = ? WHERE id = ?")
                .param(passwordHash).param(Timestamps.now()).param(id)
                .update();
    }

    public int updateStatus(long id, String status) {
        return jdbc.sql("UPDATE account SET status = ?, updated_at = ? WHERE id = ?")
                .param(status).param(Timestamps.now()).param(id)
                .update();
    }

    public int recordLogin(long id, Instant at) {
        return jdbc.sql("UPDATE account SET last_login_at = ? WHERE id = ?")
                .param(Timestamps.of(at)).param(id)
                .update();
    }

    @Transactional
    public int delete(long id) {
        jdbc.sql("DELETE FROM account_role WHERE account_id = ?").param(id).update();
        return jdbc.sql("DELETE FROM account WHERE id = ?").param(id).update();
    }

    /**
     * 仍处于启用状态的平台管理员数量。用于拒绝「禁用/删除最后一个平台管理员」，
     * 那会让系统再也无法进行平台级管理。
     */
    public int countActivePlatformAdmins(Long excludedAccountId) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(DISTINCT a.id)
                FROM account a
                JOIN account_role ar ON ar.account_id = a.id
                JOIN role r ON r.id = ar.role_id
                WHERE a.tenant_id IS NULL AND a.status = ? AND r.tenant_id IS NULL AND r.code = ?
                """);
        List<Object> params = new ArrayList<>(List.of(Account.ACTIVE, Role.PLATFORM_ADMIN));
        if (excludedAccountId != null) {
            sql.append(" AND a.id <> ?");
            params.add(excludedAccountId);
        }
        Integer count = jdbc.sql(sql.toString()).params(params).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public int countByDepartment(long departmentId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM account WHERE department_id = ?")
                .param(departmentId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public int countByPosition(long positionId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM account WHERE position_id = ?")
                .param(positionId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Account map(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                RowValues.nullableLong(rs, "tenant_id"),
                RowValues.nullableLong(rs, "department_id"),
                RowValues.nullableLong(rs, "position_id"),
                rs.getString("status"),
                rs.getString("last_login_at"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
