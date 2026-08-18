package io.github.jtconsole.migration;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.TenantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v2：创建默认套餐与默认租户，并把存量车辆、车队、围栏归入默认租户。
 *
 * <p>默认套餐刻意不限量、默认租户刻意不设有效期：升级当天的目标是「所见与升级前完全一致」，
 * 任何会立刻触发配额或到期拒绝的默认值都是升级事故。收紧留给平台管理员按商务口径手工执行。
 *
 * <p>权限目录与内置角色不在此处播种——它们随代码演进，由启动期同步组件负责，
 * 否则新版本新增的权限码永远不会进入平台管理员角色。
 */
@Component
public class V2DefaultTenantMigration implements SchemaMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger(V2DefaultTenantMigration.class);

    public static final String DEFAULT_TENANT_CODE = "default";
    public static final String DEFAULT_PLAN_NAME = "默认套餐（不限量）";

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String description() {
        return "创建默认套餐与默认租户，存量车辆/车队/围栏归入默认租户";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        String now = Timestamps.now();
        long planId = ensureDefaultPlan(jdbc, now);
        long tenantId = ensureDefaultTenant(jdbc, planId, now);
        backfill(jdbc, tenantId);
    }

    private long ensureDefaultPlan(JdbcClient jdbc, String now) {
        Long existing = jdbc.sql("SELECT id FROM plan WHERE name = ?")
                .param(DEFAULT_PLAN_NAME)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        jdbc.sql("""
                        INSERT INTO plan (name, max_vehicles, max_accounts, price_cents,
                                          period_months, enabled, remark, created_at, updated_at)
                        VALUES (?, 0, 0, 0, 12, 1, ?, ?, ?)
                        """)
                .param(DEFAULT_PLAN_NAME)
                .param("升级时自动创建；配额 0 表示不限量")
                .param(now)
                .param(now)
                .update();
        return jdbc.sql("SELECT id FROM plan WHERE name = ?")
                .param(DEFAULT_PLAN_NAME)
                .query(Long.class)
                .single();
    }

    private long ensureDefaultTenant(JdbcClient jdbc, long planId, String now) {
        Long existing = jdbc.sql("SELECT id FROM tenant WHERE code = ?")
                .param(DEFAULT_TENANT_CODE)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        jdbc.sql("""
                        INSERT INTO tenant (code, name, status, plan_id, expires_at, remark,
                                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, NULL, ?, ?, ?)
                        """)
                .param(DEFAULT_TENANT_CODE)
                .param("默认租户")
                .param(TenantStatus.ACTIVE.name())
                .param(planId)
                .param("升级时自动创建，承接租户化之前的全部存量数据")
                .param(now)
                .param(now)
                .update();
        return jdbc.sql("SELECT id FROM tenant WHERE code = ?")
                .param(DEFAULT_TENANT_CODE)
                .query(Long.class)
                .single();
    }

    private void backfill(JdbcClient jdbc, long tenantId) {
        int vehicles = jdbc.sql("UPDATE vehicle SET tenant_id = ? WHERE tenant_id IS NULL")
                .param(tenantId).update();
        int fleets = jdbc.sql("UPDATE fleet SET tenant_id = ? WHERE tenant_id IS NULL")
                .param(tenantId).update();
        int geofences = jdbc.sql("UPDATE geofence SET tenant_id = ? WHERE tenant_id IS NULL")
                .param(tenantId).update();
        LOGGER.info("存量数据归入默认租户：车辆 {}，车队 {}，围栏 {}", vehicles, fleets, geofences);
    }
}
