package io.github.jtconsole.ai.quota;

import io.github.jtconsole.domain.Plan;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.repository.AiUsageRepository;
import io.github.jtconsole.repository.PlanRepository;
import io.github.jtconsole.repository.TenantRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * AI 调用配额。形态照既有的车辆与账号配额：未绑套餐放行、上限 0 放行、超限抛配额异常。
 *
 * <p>与那两者的本质差别在于计量方式：车辆数、账号数是**存量**，一次 COUNT 就能算出来；
 * AI 调用是**流量**，「本月用了多少次」没有现成载体，必须依赖 {@code ai_usage} 这张表。
 *
 * <p>粒度是「一次对话 = 1 次」，内部工具轮数只作为观测字段记录。按 token 计费更精确，但对用户
 * 不可预期——同一个问题可能因模型多调一次工具就翻倍，按次计费才解释得清。
 */
@Service
public class AiQuotaService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final TenantRepository tenants;
    private final PlanRepository plans;
    private final AiUsageRepository usage;
    private final BusinessDateService dates;

    public AiQuotaService(
            TenantRepository tenants,
            PlanRepository plans,
            AiUsageRepository usage,
            BusinessDateService dates) {
        this.tenants = tenants;
        this.plans = plans;
        this.usage = usage;
        this.dates = dates;
    }

    public String currentMonth() {
        return dates.today().format(MONTH);
    }

    /** @throws IamException 超出本月上限 */
    public Snapshot enforceChatQuota(AuthorizedPrincipal principal) {
        Snapshot snapshot = snapshot(principal);
        if (snapshot.limit() > 0 && snapshot.used() >= snapshot.limit()) {
            throw IamException.quotaExceeded(
                    "本月 AI 调用次数已达套餐上限（" + snapshot.limit() + " 次），请联系管理员调整套餐");
        }
        return snapshot;
    }

    /** 当前用量与上限。上限 0 表示不限量。 */
    public Snapshot snapshot(AuthorizedPrincipal principal) {
        String month = currentMonth();
        long used = usage.countChatCalls(principal.tenantId(), month);
        int limit = limitOf(principal);
        return new Snapshot(used, limit, month);
    }

    private int limitOf(AuthorizedPrincipal principal) {
        if (principal.platform() || principal.tenantId() == null) {
            // 平台管理员不受租户套餐约束——他不属于任何租户，也没有可扣的额度。
            return 0;
        }
        Optional<Tenant> tenant = tenants.findById(principal.tenantId());
        if (tenant.isEmpty() || tenant.get().planId() == null) {
            return 0;
        }
        return plans.findById(tenant.get().planId()).map(Plan::maxAiCallsMonthly).orElse(0);
    }

    /** @param limit 0 表示不限量 */
    public record Snapshot(long used, int limit, String month) {
    }
}
