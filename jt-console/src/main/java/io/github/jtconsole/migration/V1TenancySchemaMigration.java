package io.github.jtconsole.migration;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v1：建立租户、账号、RBAC、组织、套餐、配置、注册与审计的全部表结构，
 * 并给车辆/车队/围栏补充归属列。
 *
 * <p>只做结构，不放种子数据：种子与结构分属两个版本后，种子失败时结构不必重来，
 * 下次启动只补种子。
 */
@Component
public class V1TenancySchemaMigration implements SchemaMigration {

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String description() {
        return "创建租户/账号/RBAC/组织/套餐/配置/注册/审计表结构，车辆、车队、围栏补充归属列";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        createTenant(jdbc);
        createAccount(jdbc);
        createRbac(jdbc);
        createOrganization(jdbc);
        createPlanAndOrder(jdbc);
        createConfigAndRegistration(jdbc);
        createAuditLog(jdbc);
        addOwnershipColumns(jdbc);
    }

    private void createTenant(JdbcClient jdbc) {
        // expires_at 为空表示永不过期，默认租户升级后即取该语义，避免存量部署突然到期。
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS tenant (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    code          TEXT NOT NULL UNIQUE,
                    name          TEXT NOT NULL,
                    status        TEXT NOT NULL DEFAULT 'ACTIVE',
                    plan_id       INTEGER,
                    expires_at    TEXT,
                    contact_name  TEXT,
                    contact_phone TEXT,
                    remark        TEXT,
                    created_at    TEXT NOT NULL,
                    updated_at    TEXT NOT NULL
                )
                """);
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_tenant_status ON tenant (status, id)");
    }

    private void createAccount(JdbcClient jdbc) {
        // tenant_id 为空即平台账号；username 全局唯一，禁用账号仍占用用户名，
        // 防止新账号冒用其历史审计与处置记录。
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS account (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    username      TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    display_name  TEXT,
                    tenant_id     INTEGER,
                    department_id INTEGER,
                    position_id   INTEGER,
                    status        TEXT NOT NULL DEFAULT 'ACTIVE',
                    last_login_at TEXT,
                    created_at    TEXT NOT NULL,
                    updated_at    TEXT NOT NULL
                )
                """);
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_account_tenant ON account (tenant_id, id)");
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_account_dept ON account (department_id)");
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_account_position ON account (position_id)");
    }

    private void createRbac(JdbcClient jdbc) {
        // 权限码目录由代码定义、启动同步；active=0 表示代码中已移除，不再参与授权。
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS permission (
                    code          TEXT PRIMARY KEY,
                    module        TEXT NOT NULL,
                    name          TEXT NOT NULL,
                    platform_only INTEGER NOT NULL DEFAULT 0,
                    sort_order    INTEGER NOT NULL DEFAULT 0,
                    active        INTEGER NOT NULL DEFAULT 1,
                    updated_at    TEXT NOT NULL
                )
                """);
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_permission_module ON permission (module, sort_order)");

        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS role (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id  INTEGER,
                    code       TEXT NOT NULL,
                    name       TEXT NOT NULL,
                    builtin    INTEGER NOT NULL DEFAULT 0,
                    data_scope TEXT NOT NULL DEFAULT 'TENANT',
                    remark     TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        // UNIQUE 里 NULL 互不相等，直接 UNIQUE(tenant_id, code) 挡不住重复的平台内置角色，
        // 因此用 COALESCE 表达式索引把平台侧归一到 0。
        execute(jdbc, """
                CREATE UNIQUE INDEX IF NOT EXISTS ux_role_scope_code
                    ON role (COALESCE(tenant_id, 0), code)
                """);

        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS role_permission (
                    role_id         INTEGER NOT NULL,
                    permission_code TEXT NOT NULL,
                    PRIMARY KEY (role_id, permission_code)
                )
                """);
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS role_dept (
                    role_id       INTEGER NOT NULL,
                    department_id INTEGER NOT NULL,
                    PRIMARY KEY (role_id, department_id)
                )
                """);
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS account_role (
                    account_id INTEGER NOT NULL,
                    role_id    INTEGER NOT NULL,
                    PRIMARY KEY (account_id, role_id)
                )
                """);
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_account_role_role ON account_role (role_id)");
    }

    private void createOrganization(JdbcClient jdbc) {
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS department (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id  INTEGER NOT NULL,
                    parent_id  INTEGER,
                    name       TEXT NOT NULL,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    enabled    INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_department_tenant
                    ON department (tenant_id, parent_id, sort_order)
                """);

        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS position (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id  INTEGER NOT NULL,
                    name       TEXT NOT NULL,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    remark     TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        execute(jdbc, """
                CREATE UNIQUE INDEX IF NOT EXISTS ux_position_tenant_name
                    ON position (tenant_id, name)
                """);
    }

    private void createPlanAndOrder(JdbcClient jdbc) {
        // max_* 为 0 表示不限量；金额一律以「分」存整数，避免浮点累计误差。
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS plan (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    name         TEXT NOT NULL UNIQUE,
                    max_vehicles INTEGER NOT NULL DEFAULT 0,
                    max_accounts INTEGER NOT NULL DEFAULT 0,
                    price_cents  INTEGER NOT NULL DEFAULT 0,
                    period_months INTEGER NOT NULL DEFAULT 12,
                    enabled      INTEGER NOT NULL DEFAULT 1,
                    remark       TEXT,
                    created_at   TEXT NOT NULL,
                    updated_at   TEXT NOT NULL
                )
                """);

        // 台账不可改删；录错以红冲负记录纠正，因此没有 updated_at。
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS tenant_order (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id           INTEGER NOT NULL,
                    plan_id             INTEGER,
                    plan_name           TEXT,
                    months              INTEGER NOT NULL,
                    amount_cents        INTEGER NOT NULL,
                    previous_expires_at TEXT,
                    new_expires_at      TEXT,
                    operator            TEXT NOT NULL,
                    remark              TEXT,
                    created_at          TEXT NOT NULL
                )
                """);
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_tenant_order_tenant
                    ON tenant_order (tenant_id, created_at DESC, id DESC)
                """);
    }

    private void createConfigAndRegistration(JdbcClient jdbc) {
        // tenant_id = 0 保留给全局默认值，与「租户覆盖 → 全局默认」的两级解析一一对应。
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS tenant_config (
                    tenant_id    INTEGER NOT NULL,
                    config_key   TEXT NOT NULL,
                    config_value TEXT,
                    updated_at   TEXT NOT NULL,
                    PRIMARY KEY (tenant_id, config_key)
                )
                """);

        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS tenant_registration (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id     INTEGER NOT NULL,
                    account_id    INTEGER NOT NULL,
                    company_name  TEXT NOT NULL,
                    contact_name  TEXT NOT NULL,
                    contact_phone TEXT NOT NULL,
                    username      TEXT NOT NULL,
                    status        TEXT NOT NULL DEFAULT 'PENDING',
                    reviewed_by   TEXT,
                    reviewed_at   TEXT,
                    review_note   TEXT,
                    source_ip     TEXT,
                    created_at    TEXT NOT NULL,
                    updated_at    TEXT NOT NULL
                )
                """);
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_registration_status
                    ON tenant_registration (status, created_at DESC, id DESC)
                """);
    }

    private void createAuditLog(JdbcClient jdbc) {
        execute(jdbc, """
                CREATE TABLE IF NOT EXISTS audit_log (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    occurred_at   TEXT NOT NULL,
                    tenant_id     INTEGER,
                    account_id    INTEGER,
                    username      TEXT,
                    action        TEXT NOT NULL,
                    resource_type TEXT,
                    resource_id   TEXT,
                    method        TEXT,
                    path          TEXT,
                    detail        TEXT,
                    source_ip     TEXT,
                    result        TEXT NOT NULL,
                    status_code   INTEGER,
                    duration_ms   INTEGER
                )
                """);
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_audit_time
                    ON audit_log (occurred_at DESC, id DESC)
                """);
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_audit_tenant_time
                    ON audit_log (tenant_id, occurred_at DESC, id DESC)
                """);
        execute(jdbc, """
                CREATE INDEX IF NOT EXISTS idx_audit_account_time
                    ON audit_log (account_id, occurred_at DESC, id DESC)
                """);
    }

    private void addOwnershipColumns(JdbcClient jdbc) {
        addColumnIfMissing(jdbc, "vehicle", "tenant_id", "INTEGER");
        addColumnIfMissing(jdbc, "vehicle", "department_id", "INTEGER");
        addColumnIfMissing(jdbc, "fleet", "tenant_id", "INTEGER");
        addColumnIfMissing(jdbc, "geofence", "tenant_id", "INTEGER");
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_vehicle_tenant ON vehicle (tenant_id, device_id)");
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_vehicle_department ON vehicle (department_id, device_id)");
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_fleet_tenant ON fleet (tenant_id, id)");
        execute(jdbc, "CREATE INDEX IF NOT EXISTS idx_geofence_tenant ON geofence (tenant_id, id)");
    }

    /**
     * SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}；版本号已保证迁移只跑一次，
     * 这里再查一次列清单是为了容忍手工改过库的环境。
     */
    private void addColumnIfMissing(JdbcClient jdbc, String table, String column, String type) {
        List<String> existing = jdbc.sql("SELECT name FROM pragma_table_info(?)")
                .param(table)
                .query(String.class)
                .list();
        boolean present = existing.stream()
                .anyMatch(name -> name.equalsIgnoreCase(column));
        if (present) {
            return;
        }
        // 表名与列名来自本类常量，不含外部输入。
        execute(jdbc, "ALTER TABLE %s ADD COLUMN %s %s"
                .formatted(table, column, type.toUpperCase(Locale.ROOT)));
    }

    private static void execute(JdbcClient jdbc, String sql) {
        jdbc.sql(sql).update();
    }
}
