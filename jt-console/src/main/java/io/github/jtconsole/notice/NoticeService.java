package io.github.jtconsole.notice;

import io.github.jtconsole.ai.briefing.BriefingService;
import io.github.jtconsole.ai.briefing.DashboardFinding;
import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Notice;
import io.github.jtconsole.domain.NoticePage;
import io.github.jtconsole.domain.NoticeView;
import io.github.jtconsole.live.LiveBroadcaster;
import io.github.jtconsole.repository.NoticeReadRepository;
import io.github.jtconsole.repository.NoticeRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.ScopeVisibility;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 把够格的发现固化成通知。
 *
 * <p>本类只做「从算出来了到送到人眼前」这一段，不参与检测，也不改写文案。
 *
 * <p><b>文案直接取 {@code finding.summary()}，不过模型。</b>那已经是代码算出来的事实陈述
 * （「京A12345 已连续 26 小时没有上报」），可以直接用。通知是无人监督时自动发出的，
 * 多一次模型调用就多一次幻觉与失败面；更要命的是模型在简报里的工作是**挑几条、把同类归并**，
 * 归并之后就丢掉了逐条身份，而抑制完全依赖那个身份。
 */
@Service
public class NoticeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoticeService.class);

    private final NoticeRepository notices;
    private final NoticeReadRepository reads;
    private final NoticeSuppressor suppressor;
    private final NoticeSettingsResolver settings;
    private final ScopeVisibility visibility;
    private final LiveBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public NoticeService(
            NoticeRepository notices,
            NoticeReadRepository reads,
            NoticeSuppressor suppressor,
            NoticeSettingsResolver settings,
            ScopeVisibility visibility,
            LiveBroadcaster broadcaster,
            ObjectMapper objectMapper) {
        this.notices = notices;
        this.reads = reads;
        this.suppressor = suppressor;
        this.settings = settings;
        this.visibility = visibility;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    /**
     * 逐条过抑制，够格的落库。
     *
     * <p>一条发现出错不影响其余：这一轮是每小时一次的批处理，为了一条发现丢掉整批
     * 是最不划算的失败方式。
     *
     * @param tenantId 归属租户；{@code 0} 是平台级作用域，与简报同一口径
     * @return 本轮真正落库的通知条数
     */
    public int emitFrom(long tenantId, List<DashboardFinding> findings) {
        if (!settings.enabled() || findings == null || findings.isEmpty()) {
            return 0;
        }
        int emitted = 0;
        for (DashboardFinding finding : findings) {
            try {
                emitted += emitOne(tenantId, finding);
            } catch (RuntimeException failure) {
                LOGGER.warn("租户 {} 的发现 {} 转通知失败：{}",
                        tenantId, finding.id(), failure.getMessage());
            }
        }
        if (emitted > 0) {
            LOGGER.info("租户 {} 新增 {} 条主动通知", tenantId, emitted);
        }
        return emitted;
    }

    private int emitOne(long tenantId, DashboardFinding finding) {
        int emitted = 0;
        for (NoticeDedupKey.Target target : NoticeDedupKey.targetsOf(finding)) {
            if (!suppressor.shouldNotify(tenantId, finding, target.deviceId())) {
                continue;
            }
            Notice row = toNotice(tenantId, finding, target);
            Optional<Long> id = notices.insert(row);
            if (id.isPresent()) {
                emitted++;
                push(tenantId, row, id.get());
            }
        }
        return emitted;
    }

    /**
     * 顺带推一条，让正在看着屏幕的人立刻知道。
     *
     * <p><b>推送是加速，不是送达保证</b>：实时通道只挂在首页与监控页两个页面上，
     * 而主动通知存在的意义恰恰是人没在看那两个页面。所以已经落库之后才推，
     * 推失败只记 debug——落库那一步已经保证了这条通知不会丢。
     *
     * <p>推的内容按租户定向而不是按设备：聚合类通知没有设备号，按设备定向的话
     * 租户管理员一条也收不到。
     */
    private void push(long tenantId, Notice row, long id) {
        try {
            broadcaster.publishToTenant("notice", tenantId, Map.of(
                    "id", id,
                    "severity", row.severity(),
                    "category", row.category(),
                    "summary", row.summary(),
                    "createdAt", row.createdAt()));
        } catch (RuntimeException undelivered) {
            LOGGER.debug("通知 {} 的实时推送失败，不影响已落库的记录：{}",
                    id, undelivered.getClass().getSimpleName());
        }
    }

    /**
     * 一条发现 + 一个去重目标 = 一条通知。
     *
     * <p><b>{@code device_ids} 只放这条通知认的那一个设备</b>，而不是发现涉及的全部：
     * 行与它的去重键必须说同一件事，否则读取时的范围过滤与抑制的粒度会对不上——
     * 一台范围外的车会把整条通知从别人眼前拿走。代价是多设备发现（目前只有默认不通知的
     * 「另有 N 台车离线」）会按台数各出一条，措辞偏笼统；那比互相牵连的抑制窗口好接受。
     */
    private Notice toNotice(long tenantId, DashboardFinding finding, NoticeDedupKey.Target target) {
        DashboardFinding.Link link = finding.link();
        return new Notice(
                0L,
                tenantId,
                target.key(),
                finding.category().name(),
                finding.severity().name(),
                finding.summary(),
                json(finding.facts(), "{}"),
                json(target.deviceId() == null ? List.of() : List.of(target.deviceId()), "[]"),
                link == null ? null : link.routeName(),
                link == null ? null : json(link.query(), "{}"),
                link == null ? null : link.label(),
                Timestamps.now());
    }

    /** 支撑数据序列化失败时退回空结构：少几个键值对，也不要因此丢掉那句话本身。 */
    private String json(Object value, String fallback) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException unserializable) {
            LOGGER.warn("通知附带数据序列化失败：{}", unserializable.getMessage());
            return fallback;
        }
    }

    // ---------------- 读取 ----------------

    /**
     * 部门受限账号的可见性过滤在内存里做，这是一次扫描的上限。
     *
     * <p>{@code device_ids} 是 JSON 列，没法在 SQL 里按数据范围过滤；而 30 天保留期内
     * 单个租户的通知量（抑制之后，每件事最多几条）远小于这个数。扫满这个窗口的租户，
     * 说明抑制配置需要调，而不是这里该加分页。
     */
    private static final int SCOPED_SCAN_LIMIT = 500;

    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 一页通知，逐条带上**该调用者自己的**已读态。
     *
     * <p>可见范围与首页要点用同一份判定（{@link ScopeVisibility}）：同一条发现要么两处都可见、
     * 要么两处都不可见。
     */
    public NoticePage read(AuthorizedPrincipal principal, DataScope scope, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        if (!canView(principal)) {
            return new NoticePage(List.of(), 0, safePage, safeSize, false);
        }
        long scopeId = BriefingService.scopeIdOf(principal);
        if (!scope.departmentRestricted()) {
            // 范围覆盖整个租户：翻页与计数都交给 SQL，页大小是精确的。
            List<Notice> rows = notices.findByTenant(scopeId, (safePage - 1) * safeSize, safeSize);
            return new NoticePage(views(principal, rows), notices.countByTenant(scopeId),
                    safePage, safeSize, false);
        }
        List<Notice> all = notices.findByTenant(scopeId, 0, SCOPED_SCAN_LIMIT);
        List<Notice> visible = visibleWithin(scope, all);
        int from = Math.min((safePage - 1) * safeSize, visible.size());
        int to = Math.min(from + safeSize, visible.size());
        return new NoticePage(
                views(principal, visible.subList(from, to)),
                visible.size(), safePage, safeSize, visible.size() < all.size());
    }

    /**
     * 未读条数，**按人计**。
     *
     * <p>范围覆盖整个租户时走一条反连接的 SQL；部门受限时按同一个扫描窗口在内存里算，
     * 与列表看到的条数保持一致——铃铛上的数字和点开后看到的必须对得上。
     */
    public long unreadCount(AuthorizedPrincipal principal, DataScope scope) {
        if (!canView(principal)) {
            return 0;
        }
        long scopeId = BriefingService.scopeIdOf(principal);
        if (!scope.departmentRestricted()) {
            return notices.countUnread(scopeId, principal.accountId());
        }
        List<Notice> visible = visibleWithin(scope, notices.findByTenant(scopeId, 0, SCOPED_SCAN_LIMIT));
        Set<Long> read = reads.readIdsOf(principal.accountId(), ids(visible));
        return visible.stream().filter(notice -> !read.contains(notice.id())).count();
    }

    /**
     * 标记一条已读。
     *
     * <p>标不到（不存在、不是本租户的、不在数据范围内）就**静默跳过**：
     * 报错与成功的差别本身就是一个探测器，能让人问出「这个 id 存在吗」。
     */
    public void markRead(AuthorizedPrincipal principal, DataScope scope, long noticeId) {
        if (!canView(principal)) {
            return;
        }
        Optional<Notice> found = notices.find(noticeId);
        if (found.isEmpty() || found.get().tenantId() != BriefingService.scopeIdOf(principal)) {
            return;
        }
        if (!visibility.forScope(scope).visible(deviceIds(found.get()))) {
            return;
        }
        reads.markRead(noticeId, principal.accountId());
    }

    /**
     * 全部标为已读——只标**这个人看得到的那些**。
     *
     * <p>直接按租户扫表会把部门受限账号根本看不到的通知也标掉，那些通知随后对他永远是已读，
     * 而他从头到尾没见过它们。
     */
    public int markAllRead(AuthorizedPrincipal principal, DataScope scope) {
        if (!canView(principal)) {
            return 0;
        }
        long scopeId = BriefingService.scopeIdOf(principal);
        if (!scope.departmentRestricted()) {
            return reads.markAllReadInTenant(scopeId, principal.accountId());
        }
        List<Notice> visible = visibleWithin(scope, notices.findByTenant(scopeId, 0, SCOPED_SCAN_LIMIT));
        return reads.markAllRead(principal.accountId(), ids(visible));
    }

    /**
     * 通知的可见性由 {@code dashboard:view} 把关，与首页要点同一个权限码。
     *
     * <p>没有这个权限的账号，可见集合为空——于是标记已读自然是空操作，
     * 不需要再造一条「拒绝」的错误路径。
     */
    private static boolean canView(AuthorizedPrincipal principal) {
        return principal.hasPermission(Permissions.DASHBOARD_VIEW);
    }

    private List<Notice> visibleWithin(DataScope scope, List<Notice> rows) {
        ScopeVisibility.Filter filter = visibility.forScope(scope);
        return rows.stream().filter(notice -> filter.visible(deviceIds(notice))).toList();
    }

    private List<NoticeView> views(AuthorizedPrincipal principal, List<Notice> rows) {
        Set<Long> read = reads.readIdsOf(principal.accountId(), ids(rows));
        return rows.stream().map(notice -> toView(notice, read.contains(notice.id()))).toList();
    }

    private static List<Long> ids(List<Notice> rows) {
        return rows.stream().map(Notice::id).toList();
    }

    private NoticeView toView(Notice notice, boolean read) {
        return new NoticeView(
                notice.id(),
                category(notice),
                severity(notice),
                notice.summary(),
                parse(notice.facts(), FACTS_TYPE, Map.of()),
                deviceIds(notice),
                link(notice),
                notice.createdAt(),
                read);
    }

    private DashboardFinding.Link link(Notice notice) {
        if (notice.linkRoute() == null || notice.linkRoute().isBlank()) {
            return null;
        }
        return new DashboardFinding.Link(
                notice.linkRoute(),
                parse(notice.linkQuery(), QUERY_TYPE, Map.of()),
                notice.linkLabel());
    }

    /**
     * 认不出的类别与严重度不让整条通知消失。
     *
     * <p>那只会是「新版本加了个类别、旧数据里存着旧值」这类情况，
     * 而通知的正文本身仍然完全可读——为了一个图标丢掉那句话不划算。
     */
    private static DashboardFinding.Category category(Notice notice) {
        try {
            return DashboardFinding.Category.valueOf(notice.category());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return DashboardFinding.Category.FLEET;
        }
    }

    private static DashboardFinding.Severity severity(Notice notice) {
        try {
            return DashboardFinding.Severity.valueOf(notice.severity());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return DashboardFinding.Severity.INFO;
        }
    }

    private List<String> deviceIds(Notice notice) {
        return parse(notice.deviceIds(), DEVICE_IDS_TYPE, List.of());
    }

    /**
     * 反序列化失败时退回空结构。
     *
     * <p>对 {@code device_ids} 而言这是**收紧**而不是放宽：空清单会被当成聚合类结论，
     * 部分数据范围一律看不到。宁可少说，不能多说。
     */
    private <T> T parse(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException malformed) {
            LOGGER.warn("通知附带数据反序列化失败：{}", malformed.getMessage());
            return fallback;
        }
    }

    private static final TypeReference<Map<String, Object>> FACTS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> QUERY_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> DEVICE_IDS_TYPE = new TypeReference<>() {};
}
