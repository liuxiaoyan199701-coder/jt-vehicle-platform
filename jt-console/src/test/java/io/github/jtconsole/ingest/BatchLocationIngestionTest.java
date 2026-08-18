package io.github.jtconsole.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.support.TestSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 0x0704 定位数据批量上传的入库行为。
 *
 * <p>补传点是历史数据，因此这里既验证「该写的都写进去了」，也验证「不该联动的一个都没联动」——
 * 用几小时前的点去开告警或判围栏，比丢掉这些点更糟。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EventIngestionIntegrationTest.TestConfiguration.class)
class BatchLocationIngestionTest {

    private static final String DEVICE = "13900000100";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EventIngestionService ingestion;

    @Autowired
    private ConsoleProperties properties;

    private int originalMaxBatchPoints;

    @BeforeEach
    void setUpDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, transactionManager);
        jdbc.sql("DROP TRIGGER IF EXISTS fail_status_write").update();
        for (String table : List.of("alarm_condition_state", "geofence_presence", "geofence_vehicle",
                "alarm_event", "geofence", "vehicle_daily_stat", "track_point", "device_status",
                "processed_event")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        originalMaxBatchPoints = properties.getIngest().getMaxBatchPoints();
    }

    @AfterEach
    void restoreProperties() {
        // Spring 上下文在测试类之间共享，改过的配置必须还原，否则会污染别的用例。
        properties.getIngest().setMaxBatchPoints(originalMaxBatchPoints);
    }

    @Test
    void everyUsablePointOfTheBatchLandsInTheTrack() {
        IngestionResult result = ingestion.ingest(batch("batch-1", point("2026-08-11T08:00:00", 39.90, 116.40),
                point("2026-08-11T08:00:30", 39.91, 116.41),
                point("2026-08-11T08:01:00", 39.92, 116.42)));

        assertThat(result.result()).isEqualTo("committed");
        assertThat(result.outcome()).isEqualTo("batch-located stored=3/3");
        assertThat(count("track_point")).isEqualTo(3);
        assertThat(deviceTime()).isEqualTo("2026-08-11T08:01:00.000+08:00");
    }

    @Test
    void unpositionedPointsAreSkippedWithoutSinkingTheRestOfTheBatch() {
        Map<String, Object> adrift = point("2026-08-11T08:00:30", 0.0, 0.0);
        Map<String, Object> notFixed = point("2026-08-11T08:00:45", 39.91, 116.41);
        notFixed.put("statusFlags", Map.of("positioned", false, "accOn", true));

        IngestionResult result = ingestion.ingest(batch("batch-2",
                point("2026-08-11T08:00:00", 39.90, 116.40), adrift, notFixed,
                point("2026-08-11T08:01:00", 39.92, 116.42)));

        assertThat(result.outcome()).isEqualTo("batch-located stored=2/4 unpositioned=2");
        assertThat(count("track_point")).isEqualTo(2);
    }

    @Test
    void aFailedBatchRollsBackEveryPoint() {
        jdbc.sql("""
                CREATE TRIGGER fail_status_write
                BEFORE INSERT ON device_status
                BEGIN
                    SELECT RAISE(ABORT, 'forced status failure');
                END
                """).update();

        MessageEnvelope envelope = batch("batch-rollback",
                point("2026-08-11T08:00:00", 39.90, 116.40),
                point("2026-08-11T08:00:30", 39.91, 116.41));
        assertThrows(RuntimeException.class, () -> ingestion.ingest(envelope));

        assertThat(count("processed_event")).isZero();
        assertThat(count("track_point")).isZero();
        assertThat(count("device_status")).isZero();

        jdbc.sql("DROP TRIGGER fail_status_write").update();
        assertThat(ingestion.ingest(envelope).result()).isEqualTo("committed");
        assertThat(count("track_point")).isEqualTo(2);
    }

    @Test
    void aBackfilledAlarmBitNeitherOpensNorClosesAnything() {
        // 先用实时单点开一条超速告警。
        ingestion.ingest(single("live-alarm", "2026-08-11T09:00:00", 39.95, 116.45, true));
        assertThat(count("alarm_event")).isEqualTo(1);
        assertThat(openAlarms()).isEqualTo(1);

        // 补传几小时前的点：一个带告警位，一个不带。两者都不该动告警。
        Map<String, Object> flagged = point("2026-08-11T07:00:00", 39.80, 116.30);
        flagged.put("alarmFlags", Map.of("overspeed", true));
        IngestionResult result = ingestion.ingest(batch("batch-alarm", flagged,
                point("2026-08-11T07:00:30", 39.81, 116.31)));

        assertThat(count("alarm_event")).isEqualTo(1);
        assertThat(openAlarms()).isEqualTo(1);
        // 历史点不推实时位置。
        assertThat(result.liveUpdate()).isNull();
    }

    @Test
    void backfilledPointsDoNotProduceGeofenceCrossings() {
        long fenceId = createGeofenceCovering(39.90, 116.40);

        ingestion.ingest(batch("batch-fence",
                point("2026-08-11T07:00:00", 60.0, 100.0),
                point("2026-08-11T07:00:30", 39.90, 116.40)));

        assertThat(count("geofence_presence")).isZero();
        assertThat(count("alarm_event")).isZero();
        assertThat(fenceId).isPositive();
    }

    @Test
    void pointsAlreadyReportedLiveAreNotStoredOrCountedTwice() {
        ingestion.ingest(single("live-1", "2026-08-11T08:00:00", 39.90, 116.40, false));
        ingestion.ingest(single("live-2", "2026-08-11T08:00:30", 39.91, 116.41, false));
        double liveDistance = distanceKm("2026-08-11");

        // 补传窗口与已实时上报的两个点重叠，只有第三个点是新的。
        IngestionResult result = ingestion.ingest(batch("batch-overlap",
                point("2026-08-11T08:00:00", 39.90, 116.40),
                point("2026-08-11T08:00:30", 39.91, 116.41),
                point("2026-08-11T08:01:00", 39.92, 116.42)));

        assertThat(result.outcome()).isEqualTo("batch-located stored=1/3");
        assertThat(count("track_point")).isEqualTo(3);
        assertThat(pointCount("2026-08-11")).isEqualTo(3);
        assertThat(distanceKm("2026-08-11")).isGreaterThan(liveDistance);
    }

    @Test
    void resendingTheSameBatchUnderANewEventIdChangesNothing() {
        Map<String, Object>[] points = points("2026-08-11T08:00:00", "2026-08-11T08:00:30");
        ingestion.ingest(batch("batch-first", points));
        double distance = distanceKm("2026-08-11");

        IngestionResult resend = ingestion.ingest(batch("batch-again", points));

        assertThat(resend.outcome()).isEqualTo("batch-located stored=0/2");
        assertThat(count("track_point")).isEqualTo(2);
        assertThat(pointCount("2026-08-11")).isEqualTo(2);
        assertThat(distanceKm("2026-08-11")).isEqualTo(distance);
    }

    @Test
    void backfillingAnEarlierWindowLeavesTheLiveViewOnTheCurrentPosition() {
        ingestion.ingest(single("live-now", "2026-08-11T12:00:00", 39.99, 116.49, false));

        ingestion.ingest(batch("batch-earlier",
                point("2026-08-11T08:00:00", 39.90, 116.40),
                point("2026-08-11T08:00:30", 39.91, 116.41)));

        assertThat(deviceTime()).isEqualTo("2026-08-11T12:00:00.000+08:00");
        assertThat(scalar("SELECT lat FROM device_status WHERE device_id = ?", Double.class))
                .isEqualTo(39.99);
        assertThat(count("track_point")).isEqualTo(3);
    }

    @Test
    void aBatchSpanningMidnightAccumulatesIntoEachOwnCalendarDay() {
        ingestion.ingest(batch("batch-midnight",
                point("2026-08-11T23:59:00", 39.90, 116.40),
                point("2026-08-11T23:59:30", 39.91, 116.41),
                point("2026-08-12T00:00:30", 39.92, 116.42),
                point("2026-08-12T00:01:00", 39.93, 116.43),
                point("2026-08-12T00:01:30", 39.94, 116.44)));

        assertThat(pointCount("2026-08-11")).isEqualTo(2);
        assertThat(pointCount("2026-08-12")).isEqualTo(3);
        assertThat(distanceKm("2026-08-11")).isPositive();
        assertThat(distanceKm("2026-08-12")).isPositive();
    }

    @Test
    void anOversizedBatchKeepsTheMostRecentPointsAndReportsWhatItDropped() {
        properties.getIngest().setMaxBatchPoints(3);

        IngestionResult result = ingestion.ingest(batch("batch-huge",
                points("2026-08-11T08:00:00", "2026-08-11T08:00:30", "2026-08-11T08:01:00",
                        "2026-08-11T08:01:30", "2026-08-11T08:02:00")));

        assertThat(result.outcome()).isEqualTo("batch-located stored=3/5 truncated=2");
        assertThat(count("track_point")).isEqualTo(3);
        // 保留的是最近的一段，当前位置必须是批次里最新的那个点。
        assertThat(deviceTime()).isEqualTo("2026-08-11T08:02:00.000+08:00");
        assertThat(earliestTrackTime()).isEqualTo("2026-08-11T08:01:00.000+08:00");

        // 截断不影响后续报文。
        ingestion.ingest(single("after-truncate", "2026-08-11T08:03:00", 39.99, 116.49, false));
        assertThat(deviceTime()).isEqualTo("2026-08-11T08:03:00.000+08:00");
    }

    @Test
    void anEmptyBatchIsRecordedWithoutTouchingTheTrack() {
        IngestionResult result = ingestion.ingest(batch("batch-empty"));

        assertThat(result.outcome()).isEqualTo("batch-empty");
        assertThat(count("track_point")).isZero();
        // 空批次仍是设备在线的证据。
        assertThat(count("device_status")).isEqualTo(1);
    }

    // ---- 构造 ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object>[] points(String... deviceTimes) {
        List<Map<String, Object>> points = new ArrayList<>();
        double lat = 39.90;
        double lng = 116.40;
        for (String deviceTime : deviceTimes) {
            points.add(point(deviceTime, lat, lng));
            lat += 0.01;
            lng += 0.01;
        }
        return points.toArray(new Map[0]);
    }

    private static Map<String, Object> point(String deviceTime, double lat, double lng) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("deviceTime", deviceTime);
        point.put("latitude", lat);
        point.put("longitude", lng);
        point.put("speedKph", 36.5D);
        point.put("direction", 90);
        point.put("altitude", 48);
        point.put("statusFlags", Map.of("positioned", true, "accOn", true));
        point.put("alarmFlags", Map.of("overspeed", false));
        return point;
    }

    @SafeVarargs
    private static MessageEnvelope batch(String eventId, Map<String, Object>... items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // payload.type 是「0 正常 / 1 盲区补报」，与信封的 type 同名不同义。
        payload.put("type", 1);
        payload.put("total", items.length);
        payload.put("items", List.of(items));
        return new MessageEnvelope(eventId, DEVICE, MessageEnvelope.BATCH_LOCATION_REPORT, 1,
                "2019", "2026-08-11T10:00:00Z", "gateway-a", "location", payload);
    }

    private static MessageEnvelope single(
            String eventId, String deviceTime, double lat, double lng, boolean overspeed) {
        Map<String, Object> payload = point(deviceTime, lat, lng);
        payload.put("alarmFlags", Map.of("overspeed", overspeed));
        return new MessageEnvelope(eventId, DEVICE, MessageEnvelope.LOCATION_REPORT, 1,
                "2019", deviceTime + "Z", "gateway-a", overspeed ? "alarm" : "location", payload);
    }

    private long createGeofenceCovering(double gcjLat, double gcjLng) {
        String now = "2026-08-11T00:00:00Z";
        jdbc.sql("""
                        INSERT INTO geofence (name, center_gcj_lat, center_gcj_lng, radius_meters,
                                              color, enabled, alert_on_enter, alert_on_exit,
                                              created_at, updated_at)
                        VALUES ('测试围栏', ?, ?, 5000, '#ff0000', 1, 1, 1, ?, ?)
                        """)
                .param(gcjLat).param(gcjLng).param(now).param(now).update();
        long fenceId = jdbc.sql("SELECT id FROM geofence WHERE name = '测试围栏'")
                .query(Long.class).single();
        jdbc.sql("INSERT INTO geofence_vehicle (geofence_id, device_id, created_at) VALUES (?, ?, ?)")
                .param(fenceId).param(DEVICE).param(now).update();
        return fenceId;
    }

    // ---- 断言辅助 ----

    private int count(String table) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    private int openAlarms() {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM alarm_event WHERE status = 'OPEN'")
                .query(Integer.class).single();
        return count == null ? 0 : count;
    }

    private String deviceTime() {
        return scalar("SELECT device_time FROM device_status WHERE device_id = ?", String.class);
    }

    private String earliestTrackTime() {
        return jdbc.sql("SELECT MIN(device_time) FROM track_point WHERE device_id = ?")
                .param(DEVICE).query(String.class).single();
    }

    private int pointCount(String date) {
        Integer count = jdbc.sql(
                        "SELECT point_count FROM vehicle_daily_stat WHERE device_id = ? AND stat_date = ?")
                .param(DEVICE).param(date).query(Integer.class).optional().orElse(0);
        return count == null ? 0 : count;
    }

    private double distanceKm(String date) {
        return jdbc.sql(
                        "SELECT distance_km FROM vehicle_daily_stat WHERE device_id = ? AND stat_date = ?")
                .param(DEVICE).param(date).query(Double.class).optional().orElse(0.0D);
    }

    private <T> T scalar(String sql, Class<T> type) {
        return jdbc.sql(sql).param(DEVICE).query(type).single();
    }
}
