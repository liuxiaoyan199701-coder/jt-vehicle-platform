package io.github.jtconsole.maintenance;

import io.github.jtconsole.audit.AuditRecorder;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.iam.RegistrationService;
import io.github.jtconsole.iam.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 租户化相关的周期任务。
 *
 * <p>到期判定在登录与网关档案接口里是实时比较有效期的，本任务只负责把状态落库并触发
 * 会话撤销与设备断连——即使调度延迟，到期租户也早已无法登录、设备也已被拒绝接入。
 */
@Component
public class TenancyMaintenanceTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenancyMaintenanceTasks.class);

    private final TenantService tenants;
    private final RegistrationService registrations;
    private final AuditRecorder audits;
    private final ConsoleProperties properties;

    public TenancyMaintenanceTasks(
            TenantService tenants,
            RegistrationService registrations,
            AuditRecorder audits,
            ConsoleProperties properties) {
        this.tenants = tenants;
        this.registrations = registrations;
        this.audits = audits;
        this.properties = properties;
    }

    /** 到期租户落库为停用并执行联动。启动后先跑一次做校准。 */
    @Scheduled(fixedDelayString = "${jt.console.tenancy.expiry-scan-millis:3600000}",
            initialDelay = 30_000L)
    public void deactivateExpiredTenants() {
        try {
            int affected = tenants.deactivateExpired();
            if (affected > 0) {
                LOGGER.info("本轮扫描停用了 {} 个到期租户", affected);
            }
        } catch (RuntimeException failure) {
            // 周期任务不能因为一次失败就停摆，下一轮会重试。
            LOGGER.warn("到期租户扫描失败：{}", failure.getClass().getSimpleName());
        }
    }

    /** 超时未处理的注册申请标记过期，避免待办列表无限堆积。 */
    @Scheduled(fixedDelay = 6 * 3_600_000L, initialDelay = 600_000L)
    public void expireStaleRegistrations() {
        try {
            registrations.expireStale();
        } catch (RuntimeException failure) {
            LOGGER.warn("注册申请过期扫描失败：{}", failure.getClass().getSimpleName());
        }
    }

    /**
     * 按保留期分批清理审计日志。放在每日低峰执行：SQLite 只有一个写锁，
     * 大批量删除与业务写入抢锁会直接体现为接口变慢。
     */
    @Scheduled(cron = "${jt.console.audit.cleanup-cron:0 17 3 * * *}")
    public void purgeAuditLog() {
        ConsoleProperties.Audit audit = properties.getAudit();
        try {
            audits.purgeOlderThan(
                    audit.getRetention(), audit.getCleanupBatchSize(), audit.getCleanupMaxBatches());
        } catch (RuntimeException failure) {
            LOGGER.warn("审计日志清理失败：{}", failure.getClass().getSimpleName());
        }
    }
}
