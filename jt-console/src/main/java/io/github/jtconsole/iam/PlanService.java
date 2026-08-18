package io.github.jtconsole.iam;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Plan;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.domain.TenantOrder;
import io.github.jtconsole.repository.PlanRepository;
import io.github.jtconsole.repository.TenantOrderRepository;
import io.github.jtconsole.repository.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 套餐与续费台账。
 *
 * <p>台账只增不改：录错以红冲（负时长、负金额）新增一条纠正。一个可以被修改的台账
 * 记录的就不再是「发生过什么」，而是「现在希望它是什么」。
 */
@Service
public class PlanService {

    private static final int MAX_NAME_LENGTH = 50;
    private static final int MAX_REMARK_LENGTH = 200;

    private final PlanRepository plans;
    private final TenantRepository tenants;
    private final TenantOrderRepository orders;
    private final Clock clock;

    @Autowired
    public PlanService(
            PlanRepository plans, TenantRepository tenants, TenantOrderRepository orders) {
        this(plans, tenants, orders, Clock.systemUTC());
    }

    PlanService(
            PlanRepository plans,
            TenantRepository tenants,
            TenantOrderRepository orders,
            Clock clock) {
        this.plans = plans;
        this.tenants = tenants;
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PlanView> list() {
        return plans.findAll().stream()
                .map(plan -> new PlanView(plan, plans.countTenants(plan.id())))
                .toList();
    }

    @Transactional
    public Plan create(PlanRequest request) {
        String name = requireText(request.name(), "套餐名称", MAX_NAME_LENGTH);
        if (plans.nameExists(name, null)) {
            throw IamException.conflict("套餐名称已存在");
        }
        String now = Timestamps.now();
        long id = plans.insert(new Plan(
                0L, name, nonNegative(request.maxVehicles(), "车辆数上限"),
                nonNegative(request.maxAccounts(), "账号数上限"),
                nonNegative(request.aiCalls(), "AI 调用上限"),
                nonNegative(request.priceCents(), "价格"),
                request.periodMonths() <= 0 ? 12 : request.periodMonths(),
                true, optionalText(request.remark(), "备注", MAX_REMARK_LENGTH), now, now));
        return plans.findById(id).orElseThrow();
    }

    @Transactional
    public Plan update(long planId, PlanRequest request) {
        Plan existing = require(planId);
        String name = requireText(request.name(), "套餐名称", MAX_NAME_LENGTH);
        if (plans.nameExists(name, planId)) {
            throw IamException.conflict("套餐名称已存在");
        }
        plans.update(new Plan(
                existing.id(), name, nonNegative(request.maxVehicles(), "车辆数上限"),
                nonNegative(request.maxAccounts(), "账号数上限"),
                nonNegative(request.aiCalls(), "AI 调用上限"),
                nonNegative(request.priceCents(), "价格"),
                request.periodMonths() <= 0 ? 12 : request.periodMonths(),
                request.enabled(), optionalText(request.remark(), "备注", MAX_REMARK_LENGTH),
                existing.createdAt(), Timestamps.now()));
        return plans.findById(planId).orElseThrow();
    }

    @Transactional
    public void delete(long planId) {
        require(planId);
        if (plans.countTenants(planId) > 0) {
            throw IamException.conflict("该套餐仍被租户绑定，请先迁移相关租户");
        }
        plans.delete(planId);
    }

    /**
     * 录入续费：台账写入与有效期延展在同一事务内完成。
     *
     * <p>延展基点取「当前有效期」与「现在」中较晚者：已过期的租户从今天起算，
     * 未到期的租户在原有效期上顺延，两种情形都不会白白吃掉已付费的时长。
     */
    @Transactional
    public TenantOrder renew(RenewRequest request, String operator) {
        Tenant tenant = tenants.findById(request.tenantId())
                .orElseThrow(() -> IamException.notFound("租户不存在"));
        if (request.months() == 0) {
            throw IamException.invalid("续费时长不能为 0");
        }

        Long planId = tenant.planId();
        String planName = null;
        if (request.planId() != null) {
            Plan plan = require(request.planId());
            planId = plan.id();
            planName = plan.name();
        } else if (planId != null) {
            planName = plans.findById(planId).map(Plan::name).orElse(null);
        }

        Instant now = clock.instant();
        Instant base = baseline(tenant, now);
        Instant extended = base.atZone(ZoneOffset.UTC)
                .plusMonths(request.months())
                .toInstant();
        String previousExpiry = tenant.expiresAt();
        tenants.updateExpiry(tenant.id(), planId, extended.toString());

        TenantOrder order = new TenantOrder(
                0L, tenant.id(), planId, planName, request.months(),
                request.amountCents(), previousExpiry, extended.toString(),
                operator == null ? "system" : operator,
                optionalText(request.remark(), "备注", MAX_REMARK_LENGTH),
                now.toString());
        orders.insert(order);
        return order;
    }

    @Transactional(readOnly = true)
    public List<TenantOrder> ordersOf(long tenantId) {
        return orders.findByTenant(tenantId);
    }

    @Transactional(readOnly = true)
    public List<TenantOrder> recentOrders(int limit) {
        return orders.findRecent(Math.clamp(limit, 1, 200));
    }

    private static Instant baseline(Tenant tenant, Instant now) {
        if (tenant.expiresAt() == null || tenant.expiresAt().isBlank()) {
            return now;
        }
        try {
            Instant current = Instant.parse(tenant.expiresAt());
            return current.isAfter(now) ? current : now;
        } catch (RuntimeException unparsable) {
            return now;
        }
    }

    private Plan require(long planId) {
        return plans.findById(planId)
                .orElseThrow(() -> IamException.notFound("套餐不存在"));
    }

    private static int nonNegative(int value, String field) {
        if (value < 0) {
            throw IamException.invalid(field + "不能为负数");
        }
        return value;
    }

    private static long nonNegative(long value, String field) {
        if (value < 0) {
            throw IamException.invalid(field + "不能为负数");
        }
        return value;
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

    /**
     * @param maxAiCallsMonthly 每月 AI 调用上限，0 或缺省表示不限量。装箱而非 {@code int}：
     *                          Jackson 3 默认拒绝把缺失字段映射成基本类型，用 {@code int} 会让
     *                          不带该字段的既有客户端直接报错
     */
    public record PlanRequest(
            String name,
            int maxVehicles,
            int maxAccounts,
            Integer maxAiCallsMonthly,
            long priceCents,
            int periodMonths,
            boolean enabled,
            String remark) {

        public int aiCalls() {
            return maxAiCallsMonthly == null ? 0 : maxAiCallsMonthly;
        }
    }

    /** 续费录入。{@code months} 允许为负，用于红冲纠错。 */
    public record RenewRequest(
            long tenantId, Long planId, int months, long amountCents, String remark) {}

    public record PlanView(Plan plan, int tenantCount) {}
}
