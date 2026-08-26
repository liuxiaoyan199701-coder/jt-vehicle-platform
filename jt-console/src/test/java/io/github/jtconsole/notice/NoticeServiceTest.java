package io.github.jtconsole.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.ai.briefing.BriefingGenerator;
import io.github.jtconsole.ai.briefing.BriefingService;
import io.github.jtconsole.ai.briefing.DashboardFinding;
import io.github.jtconsole.ai.briefing.DashboardFinding.Category;
import io.github.jtconsole.ai.briefing.DashboardFinding.Severity;
import io.github.jtconsole.ai.briefing.FindingDetectors;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.Notice;
import io.github.jtconsole.iam.TenantConfigService;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.repository.AiReportRepository;
import io.github.jtconsole.repository.NoticeRepository;
import io.github.jtconsole.repository.TenantConfigRepository;
import io.github.jtconsole.repository.TenantRepository;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.ObjectMapper;

/** 发现变成通知这一段：够格的落库、文案不过模型、失败不牵连简报。 */
class NoticeServiceTest {

    private static final long TENANT = 1L;

    private JdbcClient jdbc;
    private NoticeRepository notices;
    private ConsoleProperties properties;
    private NoticeService service;

    @BeforeEach
    void createDatabase() throws Exception {
        Path database = Files.createTempFile("jt-console-notice-service-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        jdbc = JdbcClient.create(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        notices = new NoticeRepository(jdbc);
        properties = new ConsoleProperties();
        NoticeSettingsResolver settings = new NoticeSettingsResolver(
                properties, new TenantConfigService(new TenantConfigRepository(jdbc), properties));
        service = new NoticeService(
                notices, new io.github.jtconsole.repository.NoticeReadRepository(jdbc),
                new NoticeSuppressor(notices, settings), settings,
                new io.github.jtconsole.security.ScopeVisibility(mock(io.github.jtconsole.repository.VehicleRepository.class)),
                mock(io.github.jtconsole.live.LiveBroadcaster.class), new ObjectMapper());
    }

    /** 通知的文案就是代码算出来的那句话，一个字都不该被改写。 */
    @Test
    void theNoticeSaysExactlyWhatTheDetectorComputed() {
        DashboardFinding offline = offline("offline-1", "device-1", 26);

        assertThat(service.emitFrom(TENANT, List.of(offline))).isEqualTo(1);

        Notice stored = notices.findByTenant(TENANT, 0, 10).getFirst();
        assertThat(stored.summary()).isEqualTo(offline.summary());
        assertThat(stored.severity()).isEqualTo("CRITICAL");
        assertThat(stored.dedupKey()).isEqualTo("OFFLINE:device-1");
        assertThat(stored.deviceIds()).isEqualTo("[\"device-1\"]");
        assertThat(stored.facts()).contains("离线时长(小时)").contains("26");
        assertThat(stored.linkRoute()).isEqualTo("track");
        assertThat(stored.linkQuery()).isEqualTo("{\"device\":\"device-1\"}");
        assertThat(stored.linkLabel()).isEqualTo("查看轨迹");
    }

    @Test
    void thesameFindingComingBackAnHourLaterDoesNotProduceASecondNotice() {
        DashboardFinding offline = offline("offline-1", "device-1", 8);

        assertThat(service.emitFrom(TENANT, List.of(offline))).isEqualTo(1);
        assertThat(service.emitFrom(TENANT, List.of(offline("offline-1", "device-1", 9))))
                .isZero();
        assertThat(notices.countByTenant(TENANT)).isEqualTo(1);
    }

    @Test
    void informationalFindingsStayOnTheDashboardInsteadOfInterruptingAnybody() {
        DashboardFinding informational = new DashboardFinding(
                "offline-more", Category.OFFLINE, Severity.INFO,
                "另有 3 台车离线超过 6 小时", Map.of("台数", 3),
                List.of("device-7"),
                new DashboardFinding.Link("monitor", Map.of(), "去监控页"));

        assertThat(service.emitFrom(TENANT, List.of(informational))).isZero();
        assertThat(notices.countByTenant(TENANT)).isZero();
    }

    @Test
    void raisingTheBarToCriticalSilencesMerelyWarningLevelFindings() {
        properties.getNotice().setMinSeverity("CRITICAL");

        assertThat(service.emitFrom(TENANT, List.of(
                offline("offline-1", "device-1", 8),
                offline("offline-2", "device-2", 26)))).isEqualTo(1);
        assertThat(notices.findByTenant(TENANT, 0, 10)).singleElement()
                .satisfies(only -> assertThat(only.dedupKey()).isEqualTo("OFFLINE:device-2"));
    }

    @Test
    void turningTheFeatureOffProducesNothingAtAll() {
        properties.getNotice().setEnabled(false);

        assertThat(service.emitFrom(TENANT, List.of(offline("offline-1", "device-1", 26)))).isZero();
        assertThat(notices.countByTenant(TENANT)).isZero();
    }

    /** 每条发现各自成败：一条转换不出来，其余照常落库。 */
    @Test
    void oneUnconvertibleFindingDoesNotCostTheOthers() {
        DashboardFinding unserializable = new DashboardFinding(
                "alarm-surge", Category.ALARM, Severity.WARN, "告警激增",
                Map.of("自引用", new Object() {
                    @Override
                    public String toString() {
                        throw new IllegalStateException("不可序列化");
                    }
                }),
                List.of(), null);

        service.emitFrom(TENANT, List.of(unserializable, offline("offline-1", "device-1", 26)));

        assertThat(notices.findByTenant(TENANT, 0, 10))
                .extracting(Notice::dedupKey)
                .contains("OFFLINE:device-1");
    }

    /** 通知落不下去，首页要点照常生成——那是首页的主体内容，不能被这一段拖垮。 */
    @Test
    void aFailingNoticeStageNeverTurnsTheBriefingIntoAFailure() {
        AiReportRepository reports = mock(AiReportRepository.class);
        NoticeService exploding = mock(NoticeService.class);
        when(exploding.emitFrom(anyLong(), any()))
                .thenThrow(new IllegalStateException("通知库写不进去"));

        briefingService(reports, exploding).generateFor(TENANT);

        assertThat(reportedStatus(reports)).isEqualTo("OK");
    }

    /** 模型挂了不该让通知跟着沉默：发现是代码算出来的，模型失败不影响它们的准确性。 */
    @Test
    void findingsStillReachPeopleWhenTheModelIsDown() {
        AiReportRepository reports = mock(AiReportRepository.class);
        BriefingGenerator broken = mock(BriefingGenerator.class);
        when(broken.generate(any(), any())).thenThrow(new IllegalStateException("模型不可用"));

        briefingService(reports, service, broken).generateFor(TENANT);

        assertThat(reportedStatus(reports)).isEqualTo("FAILED");
        assertThat(notices.countByTenant(TENANT)).isEqualTo(1);
    }

    private static String reportedStatus(AiReportRepository reports) {
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(reports).upsert(anyLong(), anyString(), status.capture(), anyString(),
                nullable(String.class), nullable(String.class), anyInt(), anyInt(), anyLong());
        return status.getValue();
    }

    private BriefingService briefingService(AiReportRepository reports, NoticeService sink) {
        BriefingGenerator generator = mock(BriefingGenerator.class);
        when(generator.generate(any(), any()))
                .thenReturn(new BriefingGenerator.Outcome(List.of(), "test", 1, null));
        return briefingService(reports, sink, generator);
    }

    private BriefingService briefingService(
            AiReportRepository reports, NoticeService sink, BriefingGenerator generator) {
        FindingDetectors detectors = mock(FindingDetectors.class);
        when(detectors.detect(any())).thenReturn(List.of(offline("offline-1", "device-1", 26)));
        BusinessDateService dates = mock(BusinessDateService.class);
        when(dates.today()).thenReturn(LocalDate.now());
        return new BriefingService(
                detectors, generator, reports, mock(TenantRepository.class), dates,
                new ObjectMapper(), properties, new io.github.jtconsole.security.ScopeVisibility(mock(VehicleRepository.class)), sink);
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
