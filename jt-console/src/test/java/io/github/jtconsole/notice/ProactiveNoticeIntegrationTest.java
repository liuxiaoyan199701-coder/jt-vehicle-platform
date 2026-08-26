package io.github.jtconsole.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jtconsole.ai.briefing.BriefingService;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.security.SessionTokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 端到端：一台车真的离线太久 → 下一轮简报之后，铃铛上出现未读。
 *
 * <p>这条链路横跨检测、抑制、落库与接口四段，任何一段接错都不会报错——只会安静地
 * 什么都不发生，而「安静地什么都不发生」正是这次变更要消灭的那个失效。
 * 因此逐段断言：出现、不重复、真恶化时再说一次、关掉之后彻底安静。
 *
 * <p>断言盯住**这台车那一条**（去重键 {@code OFFLINE:<设备号>}）而不是通知总数：
 * 同一轮里检测器还会产出车队在线率之类的聚合结论，那是正确行为，
 * 按总数断言只会让这个测试在检测器每次调整时无谓地红一次。
 */
@SpringBootTest
class ProactiveNoticeIntegrationTest {

    private static final String DATABASE_URL = "jdbc:sqlite:file:notice-" + UUID.randomUUID()
            + "?mode=memory&cache=shared";
    private static final String DEVICE = "013800138000";
    private static final String PLATE = "京A12345";
    private static final String OFFLINE_KEY = "OFFLINE:" + DEVICE;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private SessionTokenService tokens;
    @Autowired private BriefingService briefings;
    @Autowired private ConsoleProperties properties;

    private MockMvc mvc;
    private String bearer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("jt.console.security.ingest-key", () -> "notice-integration-ingest-key-32b!!");
        registry.add("jt.console.operations.zone-id", () -> "Asia/Shanghai");
    }

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        bearer = "Bearer " + tokens.issue(1L, "admin", null).token();
        properties.getNotice().setEnabled(true);
        properties.getNotice().setMinSeverity("WARN");
        for (String table : List.of(
                "notice_read", "notice", "ai_report", "device_status", "vehicle")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    @AfterEach
    void restoreDefaults() {
        properties.getNotice().setEnabled(true);
    }

    @Test
    void anOfflineVehicleReachesTheBellWithoutAnybodyOpeningTheDashboard() throws Exception {
        offlineFor(Duration.ofHours(8));

        // 每小时那一轮：调度器调的就是这个方法。
        briefings.generateFor(BriefingService.PLATFORM_SCOPE_ID);

        assertThat(offlineNotices()).isEqualTo(1);
        // 铃铛上的数字就是当前全部未读，一条不多一条不少。
        assertUnreadEquals(totalNotices());
        mvc.perform(get("/api/notices").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].summary", hasItem(containsString(PLATE))))
                .andExpect(jsonPath("$.data.items[*].read", hasItem(false)))
                // 点通知要能跳到该看的地方，与首页要点上同一条发现的目标一致。
                .andExpect(jsonPath("$.data.items[*].link.routeName", hasItem("track")))
                .andExpect(jsonPath("$.data.items[*].link.query.device", hasItem(DEVICE)));
    }

    @Test
    void theNextRoundAnHourLaterDoesNotSayItAgain() throws Exception {
        offlineFor(Duration.ofHours(8));
        briefings.generateFor(BriefingService.PLATFORM_SCOPE_ID);
        long afterFirstRound = totalNotices();

        offlineFor(Duration.ofHours(9));
        briefings.generateFor(BriefingService.PLATFORM_SCOPE_ID);

        assertThat(offlineNotices()).isEqualTo(1);
        assertThat(totalNotices()).isEqualTo(afterFirstRound);
    }

    /** 跨过 24 小时，WARN 翻成 CRITICAL：真实的恶化，值得再打扰一次。 */
    @Test
    void crossingIntoCriticalEarnsOneMoreInterruption() throws Exception {
        offlineFor(Duration.ofHours(8));
        briefings.generateFor(BriefingService.PLATFORM_SCOPE_ID);

        offlineFor(Duration.ofHours(26));
        briefings.generateFor(BriefingService.PLATFORM_SCOPE_ID);

        assertThat(offlineNotices()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT severity FROM notice WHERE dedup_key = ? ORDER BY created_at, id
                """).param(OFFLINE_KEY).query(String.class).list())
                .containsExactly("WARN", "CRITICAL");
    }

    @Test
    void readingOneClearsItOnlyForTheReader() throws Exception {
        offlineFor(Duration.ofHours(8));
        briefings.generateFor(BriefingService.PLATFORM_SCOPE_ID);
        long total = totalNotices();
        long id = jdbc.sql("SELECT id FROM notice WHERE dedup_key = ?")
                .param(OFFLINE_KEY).query(Long.class).single();

        mvc.perform(post("/api/notices/" + id + "/read")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        assertUnreadEquals(total - 1);
        // 已读是一条标记，不是删除——「上周提醒过我什么」要还翻得到。
        assertThat(totalNotices()).isEqualTo(total);
    }

    /** 关掉总闸后彻底安静，而首页要点照常生成——那是两件事。 */
    @Test
    void turningTheSwitchOffSilencesNoticesWhileTheBriefingCarriesOn() throws Exception {
        properties.getNotice().setEnabled(false);
        offlineFor(Duration.ofHours(26));

        briefings.generateFor(BriefingService.PLATFORM_SCOPE_ID);

        assertUnreadEquals(0);
        assertThat(totalNotices()).isZero();
        mvc.perform(get("/api/dashboard/briefing").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(not("PENDING")))
                .andExpect(jsonPath("$.data.items[*].text", hasItem(containsString(PLATE))));
    }

    private void assertUnreadEquals(long expected) throws Exception {
        mvc.perform(get("/api/notices/unread-count").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value((int) expected));
    }

    private long totalNotices() {
        return jdbc.sql("SELECT COUNT(*) FROM notice").query(Long.class).single();
    }

    private long offlineNotices() {
        return jdbc.sql("SELECT COUNT(*) FROM notice WHERE dedup_key = ?")
                .param(OFFLINE_KEY).query(Long.class).single();
    }

    /** 造一台超阈值离线的车：档案 + 一条最后上报停在指定时长之前的状态。 */
    private void offlineFor(Duration silent) {
        String lastSeen = Timestamps.of(Instant.now().minus(silent));
        jdbc.sql("""
                INSERT INTO vehicle (device_id, plate_no, channel_count, tenant_id,
                                     created_at, updated_at)
                VALUES (?, ?, 1, NULL, ?, ?)
                ON CONFLICT (device_id) DO UPDATE SET plate_no = excluded.plate_no
                """)
                .param(DEVICE).param(PLATE).param(lastSeen).param(lastSeen).update();
        jdbc.sql("""
                INSERT INTO device_status (device_id, online, last_seen_at, updated_at)
                VALUES (?, 0, ?, ?)
                ON CONFLICT (device_id) DO UPDATE SET
                    online = 0, last_seen_at = excluded.last_seen_at,
                    updated_at = excluded.updated_at
                """)
                .param(DEVICE).param(lastSeen).param(lastSeen).update();
    }
}
