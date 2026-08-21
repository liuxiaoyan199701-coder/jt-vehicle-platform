package io.github.jtconsole.support;

import io.github.jtconsole.migration.SchemaMigration;
import io.github.jtconsole.migration.SchemaMigrationRunner;
import io.github.jtconsole.migration.V1TenancySchemaMigration;
import io.github.jtconsole.migration.V2DefaultTenantMigration;
import io.github.jtconsole.migration.V3TrackPointUniquenessMigration;
import io.github.jtconsole.migration.V4AiSchemaMigration;
import io.github.jtconsole.migration.V5SessionPersistenceMigration;
import io.github.jtconsole.migration.V10GeofenceShapeMigration;
import io.github.jtconsole.migration.V11AlarmRuleMigration;
import io.github.jtconsole.migration.V12UpgradePackageMigration;
import io.github.jtconsole.migration.V13DriverManagementMigration;
import io.github.jtconsole.migration.V15WaybillMigration;
import io.github.jtconsole.migration.V16RecordingUploadMigration;
import io.github.jtconsole.migration.V17ConnectionEventsMigration;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 在手工搭建 Spring 上下文的测试里补齐增量迁移。
 *
 * <p>{@code schema.sql} 只建历史基线表，租户列与全部新表都由迁移拥有；
 * 测试若只跑 {@code schema.sql} 就会缺列，而那是生产环境不会出现的状态。
 */
public final class TestSchema {

    /** 默认租户的标识。迁移固定先建套餐再建租户，因此在空库上恒为 1。 */
    public static final long DEFAULT_TENANT_ID = 1L;

    private TestSchema() {
    }

    public static void migrate(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        List<SchemaMigration> migrations = List.of(
                new V1TenancySchemaMigration(), new V2DefaultTenantMigration(),
                new V3TrackPointUniquenessMigration(), new V4AiSchemaMigration(),
                new V5SessionPersistenceMigration(), new V10GeofenceShapeMigration(),
                new V11AlarmRuleMigration(), new V12UpgradePackageMigration(),
                new V13DriverManagementMigration(), new V15WaybillMigration(),
                new V16RecordingUploadMigration(), new V17ConnectionEventsMigration());
        new SchemaMigrationRunner(jdbc, transactionManager, migrations).afterPropertiesSet();
    }

    /** 返回默认租户的实际标识，避免测试对自增序列做硬编码假设。 */
    public static long defaultTenantId(JdbcClient jdbc) {
        return jdbc.sql("SELECT id FROM tenant WHERE code = ?")
                .param(V2DefaultTenantMigration.DEFAULT_TENANT_CODE)
                .query(Long.class)
                .single();
    }
}
