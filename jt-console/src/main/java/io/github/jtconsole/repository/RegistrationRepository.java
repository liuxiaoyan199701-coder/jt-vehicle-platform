package io.github.jtconsole.repository;

import io.github.jtconsole.domain.TenantRegistration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class RegistrationRepository {

    private static final String COLUMNS = """
            id, tenant_id, account_id, company_name, contact_name, contact_phone, username,
            status, reviewed_by, reviewed_at, review_note, source_ip, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public RegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantRegistration> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant_registration WHERE id = ?")
                .param(id).query(TenantRegistration.class).optional();
    }

    public List<TenantRegistration> findByStatus(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.sql("SELECT " + COLUMNS + """
                            FROM tenant_registration ORDER BY created_at DESC, id DESC
                            """)
                    .query(TenantRegistration.class).list();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM tenant_registration WHERE status = ?
                        ORDER BY created_at DESC, id DESC
                        """)
                .param(status).query(TenantRegistration.class).list();
    }

    public long insert(TenantRegistration registration) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO tenant_registration (tenant_id, account_id, company_name,
                                                         contact_name, contact_phone, username,
                                                         status, source_ip, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(registration.tenantId()).param(registration.accountId())
                .param(registration.companyName()).param(registration.contactName())
                .param(registration.contactPhone()).param(registration.username())
                .param(registration.status()).param(registration.sourceIp())
                .param(registration.createdAt()).param(registration.updatedAt())
                .update(key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("创建注册申请后未返回主键");
        }
        return value.longValue();
    }

    public int updateReview(long id, String status, String reviewedBy, String note) {
        String now = Instant.now().toString();
        return jdbc.sql("""
                        UPDATE tenant_registration
                        SET status = ?, reviewed_by = ?, reviewed_at = ?, review_note = ?,
                            updated_at = ?
                        WHERE id = ? AND status = ?
                        """)
                .param(status).param(reviewedBy).param(now).param(note).param(now)
                .param(id).param(TenantRegistration.PENDING)
                .update();
    }

    /** 把超过时限仍未处理的申请标记为过期，返回受影响行数。 */
    public int expirePendingBefore(String cutoff) {
        String now = Instant.now().toString();
        return jdbc.sql("""
                        UPDATE tenant_registration
                        SET status = ?, updated_at = ?
                        WHERE status = ? AND created_at < ?
                        """)
                .param(TenantRegistration.EXPIRED).param(now)
                .param(TenantRegistration.PENDING).param(cutoff)
                .update();
    }

    public List<Long> findTenantIdsByStatus(String status) {
        return jdbc.sql("SELECT tenant_id FROM tenant_registration WHERE status = ?")
                .param(status).query(Long.class).list();
    }

    public int countPending() {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM tenant_registration WHERE status = ?")
                .param(TenantRegistration.PENDING).query(Integer.class).single();
        return count == null ? 0 : count;
    }
}
