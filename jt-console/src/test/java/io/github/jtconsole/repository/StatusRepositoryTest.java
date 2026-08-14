package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.support.TestSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

/**
 * 设备最新状态的覆盖判定。
 *
 * <p>判定基准是设备时间而不是接收时间：一条 0x0704 批量补传里的所有点共享同一个接收时间，
 * 用接收时间比较会让除第一个点外的全部点被误判为过期。
 *
 * <p>注意测试里的时间形态与生产一致：{@code deviceTime} 是终端本地时间（北京时间，无时区后缀），
 * {@code receivedAt} 是 UTC。两者在 SQLite 的 {@code julianday} 下天然差 8 小时。
 */
class StatusRepositoryTest {

    private static final String DEVICE = "013800138000";

    private JdbcClient jdbc;
    private StatusRepository statuses;

    @BeforeEach
    void createDatabase() throws IOException, SQLException {
        Path database = Files.createTempFile("jt-console-status-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        jdbc = JdbcClient.create(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        statuses = new StatusRepository(jdbc);
    }

    @Test
    void aLatePacketDoesNotDragTheDeviceBackToAnEarlierPosition() {
        // 网络抖动下后发先至：较晚的定位点先到，较早的点后到。
        assertThat(report("2026-08-13T10:00:30", "2026-08-13T02:00:31Z")).isTrue();
        assertThat(report("2026-08-13T10:00:00", "2026-08-13T02:00:33Z")).isFalse();

        assertThat(deviceTime()).isEqualTo("2026-08-13T10:00:30");
    }

    @Test
    void everyPointOfOneBatchGetsAFairComparisonDespiteTheSharedReceiveTime() {
        // 一条 0x0704 的所有点共享信封接收时间；按设备时间升序投影时应当逐点前进。
        String receivedAt = "2026-08-13T02:05:00Z";
        assertThat(report("2026-08-13T10:00:00", receivedAt)).isTrue();
        assertThat(report("2026-08-13T10:00:30", receivedAt)).isTrue();
        assertThat(report("2026-08-13T10:01:00", receivedAt)).isTrue();

        assertThat(deviceTime()).isEqualTo("2026-08-13T10:01:00");
    }

    @Test
    void aRunawayDeviceClockNeitherWinsNorLocksOutTheNextNormalPoint() {
        assertThat(report("2026-08-13T10:00:00", "2026-08-13T02:00:01Z")).isTrue();

        // RTC 跳变到 2099：领先服务器时间太多，不参与覆盖竞争。
        assertThat(report("2099-01-01T00:00:00", "2026-08-13T02:00:31Z")).isFalse();
        assertThat(deviceTime()).isEqualTo("2026-08-13T10:00:00");

        // 时钟恢复后的正常点仍然能更新——脏值没有变成永久水位。
        assertThat(report("2026-08-13T10:01:00", "2026-08-13T02:01:01Z")).isTrue();
        assertThat(deviceTime()).isEqualTo("2026-08-13T10:01:00");
    }

    @Test
    void aWatermarkPoisonedBeforeThisRuleExistedIsClearedByTheNextNormalPoint() {
        assertThat(report("2026-08-13T10:00:00", "2026-08-13T02:00:01Z")).isTrue();
        // 本次变更之前写进去的脏水位：那时没有领先校验。
        jdbc.sql("UPDATE device_status SET device_time = '2099-01-01T00:00:00' WHERE device_id = ?")
                .param(DEVICE).update();

        assertThat(report("2026-08-13T10:01:00", "2026-08-13T02:01:01Z")).isTrue();
        assertThat(deviceTime()).isEqualTo("2026-08-13T10:01:00");
    }

    @Test
    void anUnparsableStoredDeviceTimeDoesNotStopTheDeviceForever() {
        assertThat(report("2026-08-13T10:00:00", "2026-08-13T02:00:01Z")).isTrue();
        jdbc.sql("UPDATE device_status SET device_time = 'not-a-time' WHERE device_id = ?")
                .param(DEVICE).update();

        assertThat(report("2026-08-13T10:01:00", "2026-08-13T02:01:01Z")).isTrue();
        assertThat(deviceTime()).isEqualTo("2026-08-13T10:01:00");
    }

    @Test
    void aPointWithoutDeviceTimeStaysOutOfTheCompetition() {
        assertThat(report("2026-08-13T10:00:00", "2026-08-13T02:00:01Z")).isTrue();

        // 没有设备时间就无从判断先后，空值不能被当成最小值参与比较。
        assertThat(report(null, "2026-08-13T02:00:31Z")).isFalse();
        assertThat(deviceTime()).isEqualTo("2026-08-13T10:00:00");
    }

    @Test
    void theEightHourGapBetweenLocalDeviceTimeAndUtcReceiveTimeIsNotTreatedAsAClockFault() {
        // 正常的中国终端每一条报文的设备时间都比接收时间「领先」8 小时，
        // 领先阈值必须宽到不去管它，否则全网设备都停止更新位置。
        String deviceTime = "2026-08-13T10:00:00";
        assertThat(report(deviceTime, Instant.parse("2026-08-13T02:00:01Z").toString())).isTrue();
        assertThat(deviceTime()).isEqualTo(deviceTime);
    }

    private boolean report(String deviceTime, String receivedAt) {
        return statuses.upsertLocation(DEVICE, deviceTime, receivedAt,
                31.23, 121.47, 31.228, 121.475,
                42.0, 90, 8, 1000.0, true, true, "[]", "[]");
    }

    private String deviceTime() {
        return jdbc.sql("SELECT device_time FROM device_status WHERE device_id = ?")
                .param(DEVICE).query(String.class).single();
    }
}
