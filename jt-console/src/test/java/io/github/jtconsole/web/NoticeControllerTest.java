package io.github.jtconsole.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.ai.briefing.BriefingGenerator;
import io.github.jtconsole.ai.briefing.BriefingItem;
import io.github.jtconsole.ai.briefing.BriefingService;
import io.github.jtconsole.ai.briefing.DashboardFinding;
import io.github.jtconsole.ai.briefing.DashboardFinding.Category;
import io.github.jtconsole.ai.briefing.DashboardFinding.Severity;
import io.github.jtconsole.ai.briefing.FindingDetectors;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.NoticePage;
import io.github.jtconsole.domain.NoticeView;
import io.github.jtconsole.iam.TenantConfigService;
import io.github.jtconsole.notice.NoticeService;
import io.github.jtconsole.notice.NoticeSettingsResolver;
import io.github.jtconsole.notice.NoticeSuppressor;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.repository.AiReportRepository;
import io.github.jtconsole.repository.NoticeReadRepository;
import io.github.jtconsole.repository.NoticeRepository;
import io.github.jtconsole.repository.TenantConfigRepository;
import io.github.jtconsole.repository.TenantRepository;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.ScopeVisibility;
import io.github.jtconsole.support.TestPrincipals;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.ObjectMapper;

/** 通知的读取接口：分页、按人计的未读、标记已读的幂等与越权。 */
class NoticeControllerTest {

    private static final long TENANT = 1L;
    private static final long OTHER_TENANT = 2L;
    private static final AuthorizedPrincipal ALICE = TestPrincipals.tenantAdmin(11L, TENANT);
    private static final AuthorizedPrincipal BOB = TestPrincipals.viewer(22L, TENANT);
    private static final AuthorizedPrincipal OUTSIDER =
            TestPrincipals.tenantAdmin(33L, OTHER_TENANT);

    private JdbcClient jdbc;
    private NoticeRepository notices;
    private NoticeService service;
    private NoticeController controller;
    private ConsoleProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        Path database = Files.createTempFile("jt-console-notice-web-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        jdbc = JdbcClient.create(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        jdbc.sql("""
                INSERT INTO tenant (id, code, name, status, plan_id, created_at, updated_at)
                SELECT 2, 'tenant-2', '租户二', 'ACTIVE', id, '2026-01-01', '2026-01-01'
                FROM plan ORDER BY id LIMIT 1
                """).update();
        vehicle("device-1", TENANT, 7L);
        vehicle("device-2", TENANT, 8L);

        notices = new NoticeRepository(jdbc);
        properties = new ConsoleProperties();
        service = noticeService(new ScopeVisibility(new VehicleRepository(jdbc)));
        controller = new NoticeController(service);
    }

    @Test
    void oneTenantNeverSeesAnothersNotices() {
        emit(TENANT, offline("offline-1", "device-1", 26));
        emit(OTHER_TENANT, offline("offline-1", "device-9", 26));

        assertThat(list(ALICE, DataScope.tenantWide(TENANT)).items())
                .singleElement()
                .satisfies(only -> assertThat(only.deviceIds()).containsExactly("device-1"));
        assertThat(list(OUTSIDER, DataScope.tenantWide(OTHER_TENANT)).items())
                .singleElement()
                .satisfies(only -> assertThat(only.deviceIds()).containsExactly("device-9"));
    }

    @Test
    void unreadCountIsCountedPerPersonNotPerNotice() {
        emit(TENANT, offline("offline-1", "device-1", 26));
        emit(TENANT, offline("offline-2", "device-2", 26));

        long noticeId = list(ALICE, DataScope.tenantWide(TENANT)).items().getFirst().id();
        controller.markRead(noticeId, ALICE, DataScope.tenantWide(TENANT));

        assertThat(unread(ALICE)).isEqualTo(1);
        assertThat(unread(BOB)).isEqualTo(2);
    }

    @Test
    void markingTheSameNoticeReadTwiceChangesNothingTheSecondTime() {
        emit(TENANT, offline("offline-1", "device-1", 26));
        long noticeId = list(ALICE, DataScope.tenantWide(TENANT)).items().getFirst().id();

        controller.markRead(noticeId, ALICE, DataScope.tenantWide(TENANT));
        controller.markRead(noticeId, ALICE, DataScope.tenantWide(TENANT));

        assertThat(unread(ALICE)).isZero();
        assertThat(list(ALICE, DataScope.tenantWide(TENANT)).items().getFirst().read()).isTrue();
    }

    /** 拿着别家的 id 来标已读：不报错也不生效，报错与成功的差别本身就是一个探测器。 */
    @Test
    void markingSomebodyElsesNoticeReadIsSilentlyIgnored() {
        emit(OTHER_TENANT, offline("offline-1", "device-9", 26));
        long foreignId = notices.findByTenant(OTHER_TENANT, 0, 10).getFirst().id();

        controller.markRead(foreignId, ALICE, DataScope.tenantWide(TENANT));

        assertThat(unread(OUTSIDER)).isEqualTo(1);
        assertThat(countReadRows()).isZero();
    }

    @Test
    void markAllReadOnlyCoversWhatThatPersonCanActuallySee() {
        emit(TENANT, offline("offline-1", "device-1", 26));
        emit(TENANT, offline("offline-2", "device-2", 26));
        DataScope onlyFirstDepartment = DataScope.departments(TENANT, Set.of(7L));

        assertThat(controller.markAllRead(BOB, onlyFirstDepartment).data().marked()).isEqualTo(1);

        assertThat(service.unreadCount(BOB, onlyFirstDepartment)).isZero();
        // 标掉的只有他看得见的那条；另一条对他仍是未读，因为他从头到尾没见过它。
        assertThat(service.unreadCount(BOB, DataScope.tenantWide(TENANT))).isEqualTo(1);
    }

    @Test
    void pagingIsClampedInsteadOfTrustingWhateverArrives() {
        for (int index = 0; index < 3; index++) {
            emit(TENANT, offline("offline-" + index, "device-1", 26 + index));
            jdbc.sql("UPDATE notice SET dedup_key = dedup_key || ?").param(index).update();
        }

        NoticePage negative = list(ALICE, DataScope.tenantWide(TENANT), -5, 0);
        assertThat(negative.page()).isEqualTo(1);
        assertThat(negative.pageSize()).isEqualTo(1);
        assertThat(negative.items()).hasSize(1);
        assertThat(negative.total()).isEqualTo(3);

        assertThat(list(ALICE, DataScope.tenantWide(TENANT), 1, 9999).pageSize()).isEqualTo(100);
        assertThat(list(ALICE, DataScope.tenantWide(TENANT), 99, 20).items()).isEmpty();
    }

    @Test
    void departmentRestrictedReadersAreToldSomethingWasHidden() {
        emit(TENANT, offline("offline-1", "device-1", 26));
        emit(TENANT, offline("offline-2", "device-2", 26));

        NoticePage page = list(BOB, DataScope.departments(TENANT, Set.of(7L)));

        assertThat(page.items()).singleElement()
                .satisfies(only -> assertThat(only.deviceIds()).containsExactly("device-1"));
        assertThat(page.filtered()).isTrue();
    }

    @Test
    void aggregateNoticesAreWithheldFromPartialScopesJustLikeOnTheDashboard() {
        emit(TENANT, new DashboardFinding(
                "alarm-surge", Category.ALARM, Severity.WARN,
                "今日新增告警 30 条，较昨日上涨 120%", Map.of("涨幅(%)", 120), List.of(), null));

        assertThat(list(ALICE, DataScope.tenantWide(TENANT)).items()).hasSize(1);
        assertThat(list(BOB, DataScope.departments(TENANT, Set.of(7L))).items()).isEmpty();
    }

    /** 通知带着与要点同一个跳转目标：点通知直接到该看的地方。 */
    @Test
    void aNoticeCarriesTheSameNavigationTargetTheDashboardWouldHaveUsed() {
        DashboardFinding finding = offline("offline-1", "device-1", 26);
        emit(TENANT, finding);

        NoticeView view = list(ALICE, DataScope.tenantWide(TENANT)).items().getFirst();

        assertThat(view.link()).isEqualTo(finding.link());
        assertThat(view.summary()).isEqualTo(finding.summary());
        assertThat(view.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(view.facts()).containsEntry("离线时长(小时)", 26);
    }

    /**
     * 同一条发现，要点里看不到的，通知里也一定看不到。
     *
     * <p>这是本变更的核心承诺，因此端到端断言而不是只测那段共用判定：
     * 把同一条发现同时写进今日要点与通知，再用同一个部门受限账号分别读，比对两边的可见性。
     */
    @Test
    void theBellAndTheDashboardNeverDisagreeAboutOneFinding() {
        DataScope restricted = DataScope.departments(TENANT, Set.of(7L));
        ScopeVisibility visibility = new ScopeVisibility(new VehicleRepository(jdbc));
        service = noticeService(visibility);
        AiReportRepository reports = new AiReportRepository(jdbc);

        for (DashboardFinding finding : List.of(
                offline("offline-1", "device-1", 26),
                offline("offline-2", "device-2", 26),
                new DashboardFinding("alarm-surge", Category.ALARM, Severity.WARN,
                        "今日新增告警 30 条", Map.of(), List.of(), null))) {
            jdbc.sql("DELETE FROM notice").update();
            service.emitFrom(TENANT, List.of(finding));
            BriefingService briefings = briefingService(reports, visibility, finding);
            briefings.generateFor(TENANT);

            boolean onTheDashboard = !briefings.read(TENANT, restricted).items().isEmpty();
            boolean inTheBell = !service.read(BOB, restricted, 1, 20).items().isEmpty();

            assertThat(inTheBell)
                    .as("发现 %s 在铃铛与首页要点里的可见性", finding.id())
                    .isEqualTo(onTheDashboard);
        }
    }

    private NoticeService noticeService(ScopeVisibility visibility) {
        NoticeSettingsResolver settings = new NoticeSettingsResolver(
                properties, new TenantConfigService(new TenantConfigRepository(jdbc), properties));
        return new NoticeService(
                notices, new NoticeReadRepository(jdbc), new NoticeSuppressor(notices, settings),
                settings, visibility, mock(io.github.jtconsole.live.LiveBroadcaster.class),
                new ObjectMapper());
    }

    /** 用真实的简报生成路径落一份今日要点，其内容就是这条发现本身。 */
    private BriefingService briefingService(
            AiReportRepository reports, ScopeVisibility visibility, DashboardFinding finding) {
        FindingDetectors detectors = mock(FindingDetectors.class);
        when(detectors.detect(any())).thenReturn(List.of(finding));
        BriefingGenerator generator = mock(BriefingGenerator.class);
        when(generator.generate(any(), any())).thenReturn(new BriefingGenerator.Outcome(
                List.of(new BriefingItem(finding.id(), finding.category(), finding.severity(),
                        finding.summary(), finding.facts(), finding.deviceIds(), finding.link())),
                "test", 1, null));
        BusinessDateService dates = mock(BusinessDateService.class);
        when(dates.today()).thenReturn(LocalDate.now());
        return new BriefingService(
                detectors, generator, reports, mock(TenantRepository.class), dates,
                new ObjectMapper(), properties, visibility, mock(NoticeService.class));
    }

    private void emit(long tenantId, DashboardFinding finding) {
        service.emitFrom(tenantId, List.of(finding));
    }

    private NoticePage list(AuthorizedPrincipal principal, DataScope scope) {
        return list(principal, scope, 1, 20);
    }

    private NoticePage list(AuthorizedPrincipal principal, DataScope scope, int page, int size) {
        return controller.list(page, size, principal, scope).data();
    }

    private long unread(AuthorizedPrincipal principal) {
        DataScope scope = DataScope.tenantWide(
                principal.tenantId() == null ? TENANT : principal.tenantId());
        return controller.unreadCount(principal, scope).data().count();
    }

    private long countReadRows() {
        return jdbc.sql("SELECT COUNT(*) FROM notice_read").query(Long.class).single();
    }

    private void vehicle(String deviceId, long tenantId, long departmentId) {
        jdbc.sql("""
                INSERT INTO vehicle (device_id, plate_no, channel_count, tenant_id,
                                     department_id, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?, '2026-01-01', '2026-01-01')
                """)
                .param(deviceId).param("京A" + deviceId).param(tenantId).param(departmentId)
                .update();
    }

    private static DashboardFinding offline(String id, String deviceId, int hours) {
        return new DashboardFinding(
                id, Category.OFFLINE,
                hours >= 24 ? Severity.CRITICAL : Severity.WARN,
                "%s 已连续 %d 小时没有上报".formatted(deviceId, hours),
                Map.of("离线时长(小时)", hours),
                List.of(deviceId),
                new DashboardFinding.Link("track", Map.of("device", deviceId), "查看轨迹"));
    }
}
