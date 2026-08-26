package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Notice;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class NoticeRepositoryTest {

    private JdbcClient jdbc;
    private NoticeRepository notices;
    private NoticeReadRepository reads;

    @BeforeEach
    void createDatabase() throws Exception {
        Path database = Files.createTempFile("jt-console-notice-", ".db");
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
        reads = new NoticeReadRepository(jdbc);
    }

    @Test
    void storedNoticesComeBackNewestFirstAndOnlyForTheOwningTenant() {
        insert(1L, "OFFLINE:device-1", "WARN", "京A00001 已连续 8 小时没有上报", minutesAgo(30));
        insert(1L, "OFFLINE:device-2", "WARN", "京A00002 已连续 9 小时没有上报", minutesAgo(10));
        insert(2L, "OFFLINE:device-9", "WARN", "别家的车离线了", minutesAgo(20));

        List<Notice> page = notices.findByTenant(1L, 0, 10);

        assertThat(page).hasSize(2);
        assertThat(page.get(0).summary()).isEqualTo("京A00002 已连续 9 小时没有上报");
        assertThat(notices.countByTenant(1L)).isEqualTo(2);
        assertThat(notices.findByTenant(2L, 0, 10)).singleElement()
                .satisfies(other -> assertThat(other.summary()).isEqualTo("别家的车离线了"));
    }

    @Test
    void factsAndLinkSurviveTheRoundTripUnchanged() {
        long id = insert(1L, "OFFLINE:device-1", "CRITICAL", "京A00001 已连续 26 小时没有上报",
                minutesAgo(5));

        Notice stored = notices.find(id).orElseThrow();

        assertThat(stored.facts()).isEqualTo(FACTS);
        assertThat(stored.deviceIds()).isEqualTo(DEVICE_IDS);
        assertThat(stored.linkRoute()).isEqualTo("track");
        assertThat(stored.linkQuery()).isEqualTo(LINK_QUERY);
        assertThat(stored.linkLabel()).isEqualTo("查看轨迹");
    }

    @Test
    void theLatestNoticeForAKeyIsWhatSuppressionReads() {
        insert(1L, "OFFLINE:device-1", "WARN", "8 小时", minutesAgo(120));
        insert(1L, "OFFLINE:device-1", "CRITICAL", "26 小时", minutesAgo(10));
        insert(1L, "OFFLINE:device-2", "WARN", "别的车", minutesAgo(1));

        assertThat(notices.findLatestByDedupKey(1L, "OFFLINE:device-1"))
                .get()
                .satisfies(latest -> {
                    assertThat(latest.severity()).isEqualTo("CRITICAL");
                    assertThat(latest.summary()).isEqualTo("26 小时");
                });
        assertThat(notices.findLatestByDedupKey(2L, "OFFLINE:device-1")).isEmpty();
    }

    /** 已读是每人一份：甲读过之后，乙的未读计数一条都不能少。 */
    @Test
    void oneReaderMarkingReadDoesNotConsumeItForAnybodyElse() {
        long first = insert(1L, "OFFLINE:device-1", "WARN", "一号车", minutesAgo(30));
        long second = insert(1L, "OFFLINE:device-2", "WARN", "二号车", minutesAgo(20));

        reads.markRead(first, 11L);

        assertThat(notices.countUnread(1L, 11L)).isEqualTo(1);
        assertThat(notices.countUnread(1L, 22L)).isEqualTo(2);
        assertThat(reads.readIdsOf(11L, List.of(first, second))).containsExactly(first);
        assertThat(reads.readIdsOf(22L, List.of(first, second))).isEmpty();
    }

    @Test
    void markingReadTwiceIsIdempotentAndKeepsTheFirstReadTime() {
        long id = insert(1L, "OFFLINE:device-1", "WARN", "一号车", minutesAgo(30));

        reads.markRead(id, 11L);
        String firstReadAt = readAtOf(id, 11L);
        reads.markRead(id, 11L);

        assertThat(readAtOf(id, 11L)).isEqualTo(firstReadAt);
        assertThat(notices.countUnread(1L, 11L)).isZero();
    }

    @Test
    void markAllReadCoversExactlyTheIdentifiersItWasGiven() {
        long first = insert(1L, "OFFLINE:device-1", "WARN", "一号车", minutesAgo(30));
        long second = insert(1L, "OFFLINE:device-2", "WARN", "二号车", minutesAgo(20));
        long third = insert(1L, "OFFLINE:device-3", "WARN", "三号车", minutesAgo(10));

        assertThat(reads.markAllRead(11L, List.of(first, second))).isEqualTo(2);

        assertThat(notices.countUnread(1L, 11L)).isEqualTo(1);
        assertThat(reads.readIdsOf(11L, List.of(first, second, third)))
                .containsExactlyInAnyOrder(first, second);
    }

    /** 清理只删过期的，并连带删掉它们的已读记录——留下孤儿行会让未读计数长期偏低。 */
    @Test
    void retentionCleanupRemovesOnlyExpiredNoticesTogetherWithTheirReadMarks() {
        long expired = insert(1L, "OFFLINE:device-1", "WARN", "很久以前", daysAgo(40));
        long fresh = insert(1L, "OFFLINE:device-2", "WARN", "昨天", daysAgo(1));
        reads.markRead(expired, 11L);
        reads.markRead(fresh, 11L);

        int removed = notices.deleteOlderThan(Instant.now().minus(Duration.ofDays(30)), 100);

        assertThat(removed).isEqualTo(1);
        assertThat(notices.find(expired)).isEmpty();
        assertThat(notices.find(fresh)).isPresent();
        assertThat(countReadRows()).isEqualTo(1);
    }

    @Test
    void retentionCleanupStopsAtTheBatchSizeSoOneRunNeverHoldsTheWriteLock() {
        for (int index = 0; index < 5; index++) {
            insert(1L, "OFFLINE:device-" + index, "WARN", "旧的", daysAgo(40));
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        assertThat(notices.deleteOlderThan(cutoff, 2)).isEqualTo(2);
        assertThat(notices.deleteOlderThan(cutoff, 2)).isEqualTo(2);
        assertThat(notices.deleteOlderThan(cutoff, 2)).isEqualTo(1);
        assertThat(notices.deleteOlderThan(cutoff, 2)).isZero();
    }

    /** 同一毫秒内的重复写入由唯一约束挡下，且不抛异常——重复是竞态的良性结果。 */
    @Test
    void aDuplicateWriteInTheSameMillisecondIsSkippedInsteadOfFailing() {
        String at = minutesAgo(5);
        assertThat(notices.insert(notice(1L, "OFFLINE:device-1", "WARN", "一号车", at))).isPresent();

        assertThat(notices.insert(notice(1L, "OFFLINE:device-1", "WARN", "一号车", at))).isEmpty();
        assertThat(notices.countByTenant(1L)).isEqualTo(1);
    }

    private static final String FACTS = "{\"车牌\":\"京A00001\",\"离线时长(小时)\":26}";
    private static final String DEVICE_IDS = "[\"device-1\"]";
    private static final String LINK_QUERY = "{\"device\":\"device-1\"}";

    private long insert(long tenantId, String key, String severity, String summary, String at) {
        return notices.insert(notice(tenantId, key, severity, summary, at)).orElseThrow();
    }

    private static Notice notice(
            long tenantId, String key, String severity, String summary, String at) {
        return new Notice(0L, tenantId, key, "OFFLINE", severity, summary,
                FACTS, DEVICE_IDS, "track", LINK_QUERY, "查看轨迹", at);
    }

    private String readAtOf(long noticeId, long accountId) {
        return jdbc.sql("SELECT read_at FROM notice_read WHERE notice_id = ? AND account_id = ?")
                .param(noticeId).param(accountId).query(String.class).single();
    }

    private long countReadRows() {
        return jdbc.sql("SELECT COUNT(*) FROM notice_read").query(Long.class).single();
    }

    private static String minutesAgo(int minutes) {
        return Timestamps.of(Instant.now().minus(Duration.ofMinutes(minutes)));
    }

    private static String daysAgo(int days) {
        return Timestamps.of(Instant.now().minus(Duration.ofDays(days)));
    }
}
