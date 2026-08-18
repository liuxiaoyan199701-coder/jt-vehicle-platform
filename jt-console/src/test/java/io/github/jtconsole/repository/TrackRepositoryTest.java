package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.domain.TrackPoint;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

/**
 * 轨迹查询的时间边界。
 *
 * <p>{@code device_time} 是带 {@code +08:00} 偏移的时间字符串，比较走 SQLite 的字节序而不是日期
 * 语义。{@code 'T'} 是 0x54、空格是 0x20，因此边界一旦写成「日期 空格 时间」，同一天的点一个都
 * 查不出来——而且缩小范围、放宽到全天都无济于事。这组测试把这条约束钉住。
 */
class TrackRepositoryTest {

    private static final String DEVICE = "138000000000";
    private static final DataScope ALL = DataScope.platform();

    private JdbcClient jdbc;
    private TrackRepository tracks;

    @BeforeEach
    void createDatabase() throws IOException, SQLException {
        Path database = Files.createTempFile("jt-console-track-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        jdbc = JdbcClient.create(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        tracks = new TrackRepository(jdbc);

        // 一段像模拟行程那样的轨迹：同一天，10 秒一个点。
        record("2026-08-17T05:16:35.000+08:00");
        record("2026-08-17T05:16:45.000+08:00");
        record("2026-08-17T05:16:55.000+08:00");
    }

    /**
     * 这条测试对应线上实际发生的故障：日统计有 9 个点、实时快照有正确地址，
     * 唯独按时间段查轨迹永远返回 0 个点。
     */
    @Test
    void findsPointsWhenTheBoundsUseASpaceInsteadOfTheStoredSeparator() {
        List<TrackPoint> points = tracks.findRange(
                DEVICE, "2026-08-17 00:00:00", "2026-08-17 12:00:00", 100, ALL);

        assertThat(points).hasSize(3);
        assertThat(points.getFirst().deviceTime()).isEqualTo("2026-08-17T05:16:35.000+08:00");
    }

    @Test
    void findsTheSamePointsWithTheStoredIsoSeparator() {
        List<TrackPoint> points = tracks.findRange(
                DEVICE, "2026-08-17T00:00:00", "2026-08-17T12:00:00", 100, ALL);

        assertThat(points).hasSize(3);
    }

    /** 缩小范围要真的起到缩小的作用，而不是把结果清零。 */
    @Test
    void narrowingTheRangeActuallyNarrowsInsteadOfReturningNothing() {
        assertThat(tracks.findRange(
                DEVICE, "2026-08-17 05:16:40", "2026-08-17 05:16:50", 100, ALL))
                .hasSize(1);
        assertThat(tracks.findRange(
                DEVICE, "2026-08-17 05:16:00", "2026-08-17 05:16:45", 100, ALL))
                .hasSize(2);
    }

    /** 用户说「查 8 月 17 号」时给的就是一个光秃秃的日期，应当落到当天整日。 */
    @Test
    void acceptsBareDatesAsWholeDayBounds() {
        assertThat(tracks.findRange(DEVICE, "2026-08-17", "2026-08-17", 100, ALL)).hasSize(3);
        assertThat(tracks.findRange(DEVICE, "2026-08-18", "2026-08-18", 100, ALL)).isEmpty();
    }

    /**
     * 边界为空表示该侧不设限。直接把 null 交给比较运算会让整个条件变成 NULL，
     * 静默返回空集——那正是「查不到」最难排查的一种形态。
     */
    @Test
    void treatsMissingBoundsAsUnbounded() {
        assertThat(tracks.findRange(DEVICE, null, null, 100, ALL)).hasSize(3);
        assertThat(tracks.findRange(DEVICE, "", "  ", 100, ALL)).hasSize(3);
        assertThat(tracks.findRange(DEVICE, "2026-08-17 05:16:45", null, 100, ALL)).hasSize(2);
    }

    @Test
    void stillExcludesPointsOutsideTheRange() {
        assertThat(tracks.findRange(
                DEVICE, "2026-08-16 00:00:00", "2026-08-16 23:59:59", 100, ALL)).isEmpty();
        assertThat(tracks.findRange(
                DEVICE, "2026-08-18 00:00:00", "2026-08-18 23:59:59", 100, ALL)).isEmpty();
    }

    private void record(String deviceTime) {
        tracks.insert(DEVICE, deviceTime, "2026-08-17T17:16:55.000+08:00",
                31.230416D, 121.473701D, 31.2323D, 121.4790D,
                60.0D, 87, 0, 3.5D, null);
    }
}
