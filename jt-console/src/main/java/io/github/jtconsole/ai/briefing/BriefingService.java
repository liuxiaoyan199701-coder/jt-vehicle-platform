package io.github.jtconsole.ai.briefing;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.notice.NoticeService;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.repository.AiReportRepository;
import io.github.jtconsole.repository.TenantRepository;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.ScopeVisibility;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 看板要点的生成与读取。
 *
 * <p>生成按**租户**进行并缓存；读取时再按调用者的数据范围过滤——这两件事必须分开，
 * 因为缓存是共享的而权限是每个人不同的。过滤规则见 {@link ScopeVisibility}，
 * 与主动通知共用同一份实现。
 */
@Service
public class BriefingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BriefingService.class);

    private final FindingDetectors detectors;
    private final BriefingGenerator generator;
    private final AiReportRepository reports;
    private final TenantRepository tenants;
    private final BusinessDateService dates;
    private final ObjectMapper objectMapper;
    private final ConsoleProperties properties;
    private final ScopeVisibility visibility;
    private final NoticeService notices;

    public BriefingService(
            FindingDetectors detectors,
            BriefingGenerator generator,
            AiReportRepository reports,
            TenantRepository tenants,
            BusinessDateService dates,
            ObjectMapper objectMapper,
            ConsoleProperties properties,
            ScopeVisibility visibility,
            NoticeService notices) {
        this.detectors = detectors;
        this.generator = generator;
        this.reports = reports;
        this.tenants = tenants;
        this.dates = dates;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.visibility = visibility;
        this.notices = notices;
    }

    /**
     * 平台级要点的存放位置。
     *
     * <p>平台管理员不属于任何租户，但它是**跨租户运维的实际使用者**——在单租户部署里，
     * 它往往就是唯一的使用者。让它永远看到空简报，等于这个功能对真正要用的人不可见。
     *
     * <p>用 0 作哨兵而不是新开一张表：{@code ai_report.tenant_id} 没有外键约束，
     * 而真实租户 id 由自增列产生、恒大于 0，两者不会撞车。
     */
    public static final long PLATFORM_SCOPE_ID = 0L;

    /**
     * 一个调用者读的是哪一份按租户生成的内容。
     *
     * <p>要点与通知**必须**用同一个映射：一旦两处对「平台管理员算哪个租户」的看法不同，
     * 就会出现铃铛与首页各说各话，而用户无从判断该信哪个。
     */
    public static long scopeIdOf(io.github.jtconsole.security.AuthorizedPrincipal principal) {
        Long tenantId = principal.tenantId();
        return tenantId == null ? PLATFORM_SCOPE_ID : tenantId;
    }

    /** 为一个租户生成并落库。定时任务与手动刷新走同一条路径。 */
    public void generateFor(long tenantId) {
        generate(tenantId, tenantId == PLATFORM_SCOPE_ID
                ? DataScope.platform()
                // 按整租户生成：缓存是共享的，读取时再按各人的范围过滤。
                : DataScope.tenantWide(tenantId));
    }

    private void generate(long tenantId, DataScope scope) {
        String today = dates.today().toString();
        List<DashboardFinding> candidates = List.of();
        try {
            candidates = detectors.detect(scope);
            BriefingGenerator.Outcome outcome = generator.generate(candidates, scope);
            reports.upsert(
                    tenantId, today,
                    outcome.error() == null ? "OK" : "DEGRADED",
                    objectMapper.writeValueAsString(outcome.items()),
                    outcome.error(),
                    outcome.model(), 0, 0, outcome.elapsedMs());
            LOGGER.info("租户 {} 的看板要点已生成：{} 条，耗时 {}ms",
                    tenantId, outcome.items().size(), outcome.elapsedMs());
        } catch (RuntimeException failure) {
            // 失败也落库：一块空白的看板分不清是「今天没事」还是「生成挂了」，
            // 而这两者的处理方式完全相反。
            LOGGER.warn("租户 {} 的看板要点生成失败：{}", tenantId, failure.getMessage());
            reports.upsert(tenantId, today, "FAILED", "[]", failure.getMessage(), "", 0, 0, 0);
        }
        emitNotices(tenantId, candidates);
    }

    /**
     * 把这一轮的候选发现送去产出主动通知。
     *
     * <p>放在 try 之外、**不看简报成败**：发现是代码算出来的，模型挂掉不影响它们的准确性，
     * 而「算出来了却没人被告知」正是主动通知要解决的那件事。检测本身失败时候选为空，
     * 这里自然什么都不做。
     *
     * <p>反过来，通知这一段失败只记 warn：简报是首页的主体内容，不能因为一条通知写不进去
     * 就让整块看板变成「生成失败」。
     */
    private void emitNotices(long tenantId, List<DashboardFinding> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        try {
            notices.emitFrom(tenantId, candidates);
        } catch (RuntimeException failure) {
            LOGGER.warn("租户 {} 的主动通知生成失败：{}", tenantId, failure.getMessage());
        }
    }

    /** 逐租户串行生成，外加一份平台级的。一个失败不影响其它。 */
    public void generateAll() {
        generateFor(PLATFORM_SCOPE_ID);
        for (Tenant tenant : tenants.findAll()) {
            generateFor(tenant.id());
        }
    }

    /**
     * 读取要点，按调用者的数据范围过滤。
     *
     * @param scope 调用者的数据范围
     */
    public Briefing read(long tenantId, DataScope scope) {
        String today = dates.today().toString();
        Optional<AiReportRepository.Row> row = reports.find(tenantId, today);
        if (row.isEmpty()) {
            return Briefing.pending();
        }
        AiReportRepository.Row report = row.get();
        List<BriefingItem> stored = parse(report.contentJson());
        // 过滤规则与主动通知共用同一份实现，见 ScopeVisibility——两处各写一遍必然分叉，
        // 而分叉的表现是「铃铛里有但首页要点里没有」，用户无从判断该信哪个。
        ScopeVisibility.Filter filter = visibility.forScope(scope);
        List<BriefingItem> visible = stored.stream()
                .filter(item -> filter.visible(item.deviceIds()))
                .toList();
        return new Briefing(
                visible,
                report.status(),
                report.updatedAt(),
                report.error(),
                visible.size() < stored.size());
    }

    private List<BriefingItem> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<BriefingItem>>() {});
        } catch (RuntimeException malformed) {
            LOGGER.warn("看板要点反序列化失败：{}", malformed.getMessage());
            return List.of();
        }
    }

    /** 清理过期要点。 */
    public int purgeExpired() {
        String cutoff = dates.today()
                .minusDays(properties.getAi().getBriefing().getRetention().toDays())
                .toString();
        return reports.deleteOlderThan(cutoff);
    }

    /**
     * @param filtered 是否因数据范围而隐藏了部分要点。前端据此说明「已按你的数据范围过滤」，
     *                 免得用户以为平台漏报
     */
    public record Briefing(
            List<BriefingItem> items,
            String status,
            String updatedAt,
            String error,
            boolean filtered) {

        /** 尚未生成过。前端显示「正在准备今日要点」而不是「今天没事」。 */
        public static Briefing pending() {
            return new Briefing(List.of(), "PENDING", null, null, false);
        }

        /** 该账号没有所属租户（平台管理员），本功能对它不适用。 */
        public static Briefing none() {
            return new Briefing(List.of(), "NONE", null, null, false);
        }
    }
}
