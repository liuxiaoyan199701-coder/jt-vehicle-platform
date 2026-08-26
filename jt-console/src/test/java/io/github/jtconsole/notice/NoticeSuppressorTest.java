package io.github.jtconsole.notice;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.ai.briefing.DashboardFinding;
import io.github.jtconsole.ai.briefing.DashboardFinding.Category;
import io.github.jtconsole.ai.briefing.DashboardFinding.Severity;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Notice;
import io.github.jtconsole.iam.TenantConfigService;
import io.github.jtconsole.repository.NoticeRepository;
import io.github.jtconsole.repository.TenantConfigRepository;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

/**
 * 抑制判定。
 *
 * <p>本类的第一个用例是这次变更里唯一「做错了不报错」的地方的回归网：
 * 去重键若误用 {@code finding.id()}，离线发现的序号一漂移就会重新通知，
 * 而离线恰恰是最常见的一类，最终结果是用户永久关掉这个功能。
 */
class NoticeSuppressorTest {

    private static final long TENANT = 1L;

    private JdbcClient jdbc;
    private NoticeRepository notices;
    private ConsoleProperties properties;
    private NoticeSuppressor suppressor;

    @BeforeEach
    void createDatabase() throws Exception {
        Path database = Files.createTempFile("jt-console-notice-suppress-", ".db");
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
        suppressor = new NoticeSuppressor(notices, new NoticeSettingsResolver(
                properties, new TenantConfigService(new TenantConfigRepository(jdbc), properties)));
    }

    /**
     * 一台车持续离线期间，另一台离线更久的车出现，把前者从 {@code offline-1} 挤成
     * {@code offline-2}。**认的是那台车，不是它排第几**——不产生第二条通知。
     */
    @Test
    void aVehicleSlippingDownTheOfflineRankingIsStillTheSameVehicle() {
        DashboardFinding firstRound = offline("offline-1", "device-1", 8);
        assertThat(suppressor.shouldNotify(TENANT, firstRound, "device-1")).isTrue();
        store(firstRound, "device-1", minutesAgo(60));

        // 一小时后：device-2 离线 30 小时，排到了前面，device-1 的展示 id 随之漂移。
        DashboardFinding driftedSameVehicle = offline("offline-2", "device-1", 9);
        DashboardFinding newcomer = offline("offline-1", "device-2", 30);

        assertThat(suppressor.shouldNotify(TENANT, driftedSameVehicle, "device-1")).isFalse();
        assertThat(suppressor.shouldNotify(TENANT, newcomer, "device-2")).isTrue();
    }

    @Test
    void aProblemThatKeepsExistingIsAnnouncedOnceNotOncePerHour() {
        DashboardFinding finding = offline("offline-1", "device-1", 8);
        store(finding, "device-1", minutesAgo(120));

        assertThat(suppressor.shouldNotify(TENANT, offline("offline-1", "device-1", 9), "device-1"))
                .isFalse();
        assertThat(suppressor.shouldNotify(TENANT, offline("offline-1", "device-1", 10), "device-1"))
                .isFalse();
    }

    @Test
    void onceTheSilenceWindowHasPassedTheSameProblemMayBeRaisedAgain() {
        store(offline("offline-1", "device-1", 8), "device-1", hoursAgo(25));

        assertThat(suppressor.shouldNotify(TENANT, offline("offline-1", "device-1", 33), "device-1"))
                .isTrue();
    }

    /** 离线跨过 24 小时由 WARN 翻成 CRITICAL：那是真实的恶化，压掉它等于把最该说的那次说没了。 */
    @Test
    void realDeteriorationEscapesTheSilenceWindow() {
        store(offline("offline-1", "device-1", 8), "device-1", minutesAgo(30));

        DashboardFinding worse = offline("offline-1", "device-1", 26);
        assertThat(worse.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(suppressor.shouldNotify(TENANT, worse, "device-1")).isTrue();
    }

    @Test
    void severityFallingBackDoesNotEarnAnotherInterruption() {
        store(offline("offline-1", "device-1", 26), "device-1", minutesAgo(30));

        assertThat(suppressor.shouldNotify(TENANT, offline("offline-1", "device-1", 8), "device-1"))
                .isFalse();
    }

    /** 升级只豁免一次：CRITICAL 通知过之后，接下来的 CRITICAL 仍按窗口压住。 */
    @Test
    void escalationIsExemptOnceNotForever() {
        store(offline("offline-1", "device-1", 8), "device-1", hoursAgo(2));
        store(offline("offline-1", "device-1", 26), "device-1", hoursAgo(1));

        assertThat(suppressor.shouldNotify(TENANT, offline("offline-1", "device-1", 27), "device-1"))
                .isFalse();
    }

    /** 严重的事窗口更短：CRITICAL 默认 6 小时后可以再说一次，而 WARN 要等满 24 小时。 */
    @Test
    void criticalGetsAShorterSilenceWindowThanWarn() {
        store(offline("offline-1", "device-1", 26), "device-1", hoursAgo(7));
        store(offline("offline-1", "device-2", 8), "device-2", hoursAgo(7));

        assertThat(suppressor.shouldNotify(TENANT, offline("offline-1", "device-1", 33), "device-1"))
                .isTrue();
        assertThat(suppressor.shouldNotify(TENANT, offline("offline-2", "device-2", 15), "device-2"))
                .isFalse();
    }

    /** 「另有 N 台车离线」这类是看板上的补充信息，不是需要打断人的事。 */
    @Test
    void informationalFindingsDoNotInterruptAnybodyByDefault() {
        DashboardFinding informational = new DashboardFinding(
                "offline-more", Category.OFFLINE, Severity.INFO,
                "另有 3 台车离线超过 6 小时", Map.of("台数", 3),
                List.of("device-7"), null);

        assertThat(suppressor.shouldNotify(TENANT, informational, "device-7")).isFalse();
    }

    @Test
    void aTenantRaisingTheBarStopsBeingToldAboutMerelyWarningLevelFindings() {
        jdbc.sql("""
                INSERT INTO tenant_config (tenant_id, config_key, config_value, updated_at)
                VALUES (?, 'notice.minSeverity', 'CRITICAL', '2026-08-25T00:00:00.000+08:00')
                """).param(TENANT).update();

        assertThat(suppressor.shouldNotify(TENANT, offline("offline-1", "device-1", 8), "device-1"))
                .isFalse();
        assertThat(suppressor.shouldNotify(TENANT, offline("offline-1", "device-2", 26), "device-2"))
                .isTrue();
    }

    @Test
    void aTenantShorteningItsWindowIsToldAgainSooner() {
        jdbc.sql("""
                INSERT INTO tenant_config (tenant_id, config_key, config_value, updated_at)
                VALUES (?, 'notice.silenceWindow.warnHours', '2', '2026-08-25T00:00:00.000+08:00')
                """).param(TENANT).update();
        store(offline("offline-1", "device-1", 8), "device-1", hoursAgo(3));

        assertThat(suppressor.shouldNotify(TENANT, offline("offline-1", "device-1", 11), "device-1"))
                .isTrue();
    }

    /** 抑制窗口是每个租户各自的：甲租户通知过，不会让乙租户的同名设备被压住。 */
    @Test
    void oneTenantsSilenceWindowNeverReachesIntoAnother() {
        store(offline("offline-1", "device-1", 8), "device-1", minutesAgo(30));

        assertThat(suppressor.shouldNotify(2L, offline("offline-1", "device-1", 8), "device-1"))
                .isTrue();
    }

    /** 聚合类发现没有设备号，认自己的稳定 id。 */
    @Test
    void aggregateFindingsAreKeyedByTheirOwnStableIdentifier() {
        DashboardFinding surge = new DashboardFinding(
                "alarm-surge", Category.ALARM, Severity.WARN,
                "今日新增告警 30 条，较昨日上涨 120%", Map.of("涨幅(%)", 120),
                List.of(), null);

        assertThat(suppressor.shouldNotify(TENANT, surge, null)).isTrue();
        store(surge, null, minutesAgo(30));
        assertThat(suppressor.shouldNotify(TENANT, surge, null)).isFalse();
    }

    private void store(DashboardFinding finding, String deviceId, String createdAt) {
        notices.insert(new Notice(
                0L, TENANT, NoticeDedupKey.of(finding, deviceId),
                finding.category().name(), finding.severity().name(), finding.summary(),
                "{}", deviceId == null ? "[]" : "[\"" + deviceId + "\"]",
                null, null, null, createdAt));
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

    private static String minutesAgo(int minutes) {
        return Timestamps.of(Instant.now().minus(Duration.ofMinutes(minutes)));
    }

    private static String hoursAgo(int hours) {
        return Timestamps.of(Instant.now().minus(Duration.ofHours(hours)));
    }
}
