package io.github.jtconsole.repository;

import io.github.jtconsole.domain.TenantOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 续费台账。刻意不提供 update 与 delete：录错以红冲负记录纠正，
 * 台账一旦可改就失去了「记录发生过什么」的意义。
 */
@Repository
public class TenantOrderRepository {

    private static final String COLUMNS = """
            id, tenant_id, plan_id, plan_name, months, amount_cents, previous_expires_at,
            new_expires_at, operator, remark, created_at
            """;

    private final JdbcClient jdbc;

    public TenantOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TenantOrder order) {
        jdbc.sql("""
                        INSERT INTO tenant_order (tenant_id, plan_id, plan_name, months,
                                                  amount_cents, previous_expires_at,
                                                  new_expires_at, operator, remark, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(order.tenantId()).param(order.planId()).param(order.planName())
                .param(order.months()).param(order.amountCents())
                .param(order.previousExpiresAt()).param(order.newExpiresAt())
                .param(order.operator()).param(order.remark()).param(order.createdAt())
                .update();
    }

    public List<TenantOrder> findByTenant(long tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM tenant_order WHERE tenant_id = ?
                        ORDER BY created_at DESC, id DESC
                        """)
                .param(tenantId).query(TenantOrderRepository::map).list();
    }

    public List<TenantOrder> findRecent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM tenant_order ORDER BY created_at DESC, id DESC LIMIT ?
                        """)
                .param(limit).query(TenantOrderRepository::map).list();
    }

    private static TenantOrder map(ResultSet rs, int rowNum) throws SQLException {
        return new TenantOrder(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                RowValues.nullableLong(rs, "plan_id"),
                rs.getString("plan_name"),
                rs.getInt("months"),
                rs.getLong("amount_cents"),
                rs.getString("previous_expires_at"),
                rs.getString("new_expires_at"),
                rs.getString("operator"),
                rs.getString("remark"),
                rs.getString("created_at"));
    }
}
