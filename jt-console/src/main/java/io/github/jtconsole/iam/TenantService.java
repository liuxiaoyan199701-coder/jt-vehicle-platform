package io.github.jtconsole.iam;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Plan;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.domain.TenantStatus;
import io.github.jtconsole.gateway.DeviceDisconnectClient;
import io.github.jtconsole.repository.PlanRepository;
import io.github.jtconsole.repository.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户档案与状态。
 *
 * <p>停用与到期共享同一套联动：撤销该租户全部会话、让网关档案接口返回非活跃、批量断开其设备。
 * 断连失败只记录不阻塞——停用的核心保障（登录与会话即时失效）在控制台侧已经生效，
 * 让网关不可达把停用整个卡住反而更糟。
 */
@Service
public class TenantService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantService.class);
    private static final int MAX_CODE_LENGTH = 32;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_CONTACT_LENGTH = 50;
    private static final int MAX_REMARK_LENGTH = 500;

    private final TenantRepository tenants;
    private final PlanRepository plans;
    private final AccountService accountSessions;
    private final DeviceDisconnectClient disconnects;
    private final Clock clock;

    @Autowired
    public TenantService(
            TenantRepository tenants,
            PlanRepository plans,
            AccountService accountSessions,
            DeviceDisconnectClient disconnects) {
        this(tenants, plans, accountSessions, disconnects, Clock.systemUTC());
    }

    TenantService(
            TenantRepository tenants,
            PlanRepository plans,
            AccountService accountSessions,
            DeviceDisconnectClient disconnects,
            Clock clock) {
        this.tenants = tenants;
        this.plans = plans;
        this.accountSessions = accountSessions;
        this.disconnects = disconnects;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TenantView> list() {
        Instant now = clock.instant();
        return tenants.findAll().stream().map(tenant -> toView(tenant, now)).toList();
    }

    @Transactional(readOnly = true)
    public TenantView get(long tenantId) {
        Tenant tenant = require(tenantId);
        return toView(tenant, clock.instant());
    }

    @Transactional
    public TenantView create(TenantRequest request) {
        String code = requireText(request.code(), "租户编码", MAX_CODE_LENGTH);
        String name = requireText(request.name(), "租户名称", MAX_NAME_LENGTH);
        if (tenants.codeExists(code, null)) {
            throw IamException.conflict("租户编码已被占用");
        }
        Long planId = validatePlan(request.planId());
        String now = Timestamps.now();
        long id = tenants.insert(new Tenant(
                0L, code, name, TenantStatus.ACTIVE.name(), planId,
                normalizedExpiry(request.expiresAt()),
                optionalText(request.contactName(), "联系人", MAX_CONTACT_LENGTH),
                optionalText(request.contactPhone(), "联系电话", MAX_CONTACT_LENGTH),
                optionalText(request.remark(), "备注", MAX_REMARK_LENGTH),
                now, now));
        return toView(require(id), clock.instant());
    }

    @Transactional
    public TenantView update(long tenantId, TenantRequest request) {
        Tenant existing = require(tenantId);
        String code = requireText(request.code(), "租户编码", MAX_CODE_LENGTH);
        String name = requireText(request.name(), "租户名称", MAX_NAME_LENGTH);
        if (tenants.codeExists(code, tenantId)) {
            throw IamException.conflict("租户编码已被占用");
        }
        Long planId = validatePlan(request.planId());
        tenants.update(new Tenant(
                existing.id(), code, name, existing.status(), planId,
                normalizedExpiry(request.expiresAt()),
                optionalText(request.contactName(), "联系人", MAX_CONTACT_LENGTH),
                optionalText(request.contactPhone(), "联系电话", MAX_CONTACT_LENGTH),
                optionalText(request.remark(), "备注", MAX_REMARK_LENGTH),
                existing.createdAt(), Timestamps.now()));
        Tenant updated = require(tenantId);
        // 编辑可能把有效期改到过去，等同于立刻到期。
        if (!updated.active(clock.instant())) {
            applyDeactivation(updated);
        }
        return toView(updated, clock.instant());
    }

    @Transactional
    public TenantView changeStatus(long tenantId, boolean enabled) {
        Tenant existing = require(tenantId);
        TenantStatus current = existing.statusValue();
        if (current == TenantStatus.PENDING_APPROVAL || current == TenantStatus.REJECTED) {
            throw IamException.conflict("注册申请尚未通过审批，不能直接启用或停用");
        }
        tenants.updateStatus(tenantId, enabled ? TenantStatus.ACTIVE : TenantStatus.SUSPENDED);
        Tenant updated = require(tenantId);
        if (!enabled) {
            applyDeactivation(updated);
        }
        return toView(updated, clock.instant());
    }

    @Transactional
    public void delete(long tenantId) {
        require(tenantId);
        if (tenants.countVehicles(tenantId) > 0) {
            throw IamException.conflict("该租户仍有车辆，请先调拨或删除车辆");
        }
        if (tenants.countAccounts(tenantId) > 0) {
            throw IamException.conflict("该租户仍有账号，请先删除账号");
        }
        tenants.delete(tenantId);
        accountSessions.revokeTenantSessions(tenantId);
    }

    /** 到期扫描：把已过期的活跃租户落库为停用，并执行同一套联动。 */
    @Transactional
    public int deactivateExpired() {
        Instant now = clock.instant();
        int affected = 0;
        for (Tenant tenant : tenants.findByStatus(TenantStatus.ACTIVE)) {
            if (!tenant.expired(now)) {
                continue;
            }
            tenants.updateStatus(tenant.id(), TenantStatus.SUSPENDED);
            applyDeactivation(tenant);
            affected++;
            LOGGER.info("租户 {} 已到期，自动停用", tenant.code());
        }
        return affected;
    }

    /** 租户失效的统一联动：撤销会话、失效授权缓存、断开该租户全部设备。 */
    private void applyDeactivation(Tenant tenant) {
        accountSessions.revokeTenantSessions(tenant.id());
        List<String> deviceIds = tenants.findDeviceIds(tenant.id());
        if (!deviceIds.isEmpty()) {
            disconnects.disconnectQuietly(deviceIds, tenant.code());
        }
    }

    @Transactional(readOnly = true)
    public Tenant require(long tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> IamException.notFound("租户不存在"));
    }

    private Long validatePlan(Long planId) {
        if (planId == null) {
            return null;
        }
        Plan plan = plans.findById(planId)
                .orElseThrow(() -> IamException.notFound("套餐不存在"));
        if (!plan.enabled()) {
            throw IamException.invalid("该套餐已停用，不能用于新的绑定");
        }
        return plan.id();
    }

    private static String normalizedExpiry(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return null;
        }
        try {
            return Timestamps.of(Instant.parse(expiresAt.trim()));
        } catch (RuntimeException unparsable) {
            throw IamException.invalid("有效期格式不正确，应为 ISO-8601 时间");
        }
    }

    private TenantView toView(Tenant tenant, Instant now) {
        String planName = tenant.planId() == null
                ? null
                : plans.findById(tenant.planId()).map(Plan::name).orElse(null);
        Optional<Plan> plan = tenant.planId() == null
                ? Optional.empty()
                : plans.findById(tenant.planId());
        return new TenantView(
                tenant,
                planName,
                plan.map(Plan::maxVehicles).orElse(0),
                plan.map(Plan::maxAccounts).orElse(0),
                tenants.countVehicles(tenant.id()),
                tenants.countAccounts(tenant.id()),
                tenant.expired(now),
                tenant.active(now));
    }

    private static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw IamException.invalid(field + "不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw IamException.invalid(field + "最长 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > maxLength) {
            throw IamException.invalid(field + "最长 " + maxLength + " 个字符");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record TenantRequest(
            String code,
            String name,
            Long planId,
            String expiresAt,
            String contactName,
            String contactPhone,
            String remark) {}

    /** 租户列表读模型，附带套餐配额与当前用量。 */
    public record TenantView(
            Tenant tenant,
            String planName,
            int maxVehicles,
            int maxAccounts,
            int vehicleCount,
            int accountCount,
            boolean expired,
            boolean active) {}
}
