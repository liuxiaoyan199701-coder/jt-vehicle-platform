package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Plan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class PlanRepository {

    private static final String COLUMNS = """
            id, name, max_vehicles, max_accounts, max_ai_calls_monthly, price_cents,
            period_months, enabled, remark, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Plan> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM plan WHERE id = ?")
                .param(id).query(PlanRepository::map).optional();
    }

    public List<Plan> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM plan ORDER BY id")
                .query(PlanRepository::map).list();
    }

    public boolean nameExists(String name, Long excludedId) {
        String sql = excludedId == null
                ? "SELECT COUNT(*) FROM plan WHERE name = ?"
                : "SELECT COUNT(*) FROM plan WHERE name = ? AND id <> ?";
        var spec = jdbc.sql(sql).param(name);
        if (excludedId != null) {
            spec = spec.param(excludedId);
        }
        Integer count = spec.query(Integer.class).single();
        return count != null && count > 0;
    }

    public long insert(Plan plan) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO plan (name, max_vehicles, max_accounts, max_ai_calls_monthly,
                                          price_cents, period_months, enabled, remark,
                                          created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(plan.name()).param(plan.maxVehicles()).param(plan.maxAccounts())
                .param(plan.maxAiCallsMonthly()).param(plan.priceCents())
                .param(plan.periodMonths()).param(plan.enabled() ? 1 : 0)
                .param(plan.remark()).param(plan.createdAt()).param(plan.updatedAt())
                .update(key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("创建套餐后未返回主键");
        }
        return value.longValue();
    }

    public int update(Plan plan) {
        return jdbc.sql("""
                        UPDATE plan SET name = ?, max_vehicles = ?, max_accounts = ?,
                            max_ai_calls_monthly = ?, price_cents = ?, period_months = ?,
                            enabled = ?, remark = ?, updated_at = ?
                        WHERE id = ?
                        """)
                .param(plan.name()).param(plan.maxVehicles()).param(plan.maxAccounts())
                .param(plan.maxAiCallsMonthly()).param(plan.priceCents())
                .param(plan.periodMonths()).param(plan.enabled() ? 1 : 0)
                .param(plan.remark()).param(Timestamps.now()).param(plan.id())
                .update();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM plan WHERE id = ?").param(id).update();
    }

    public int countTenants(long planId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM tenant WHERE plan_id = ?")
                .param(planId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    private static Plan map(ResultSet rs, int rowNum) throws SQLException {
        return new Plan(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("max_vehicles"),
                rs.getInt("max_accounts"),
                rs.getInt("max_ai_calls_monthly"),
                rs.getLong("price_cents"),
                rs.getInt("period_months"),
                RowValues.flag(rs, "enabled"),
                rs.getString("remark"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
