package io.github.jtconsole.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtconsole.domain.LiveStatus;
import io.github.jtconsole.repository.EventRepository;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.AlarmRuleRepository;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.repository.DeviceAttributeRepository;
import io.github.jtconsole.repository.DriverRepository;
import io.github.jtconsole.repository.GeofenceRepository;
import io.github.jtconsole.repository.MediaRepository;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.repository.TrackRepository;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.web.VehicleController;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.operations.AlarmService;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.operations.DailyStatService;
import io.github.jtconsole.operations.GeofenceService;
import io.github.jtconsole.operations.RuleService;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.iam.OrganizationService;
import io.github.jtconsole.repository.PlanRepository;
import io.github.jtconsole.repository.TenantRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestPrincipals;
import io.github.jtconsole.support.TestSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EventIngestionIntegrationTest.TestConfiguration.class)
class EventIngestionIntegrationTest {

    private static final String START = "2026-08-11T00:00:00";
    private static final String END = "2026-08-11T23:59:59";
    /** 平台视角：跨租户可见，含未建档设备。 */
    private static final DataScope SCOPE = DataScope.platform();
    private static long TENANT_ID;

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcClient jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    private EventIngestionService ingestion;

    @org.springframework.beans.factory.annotation.Autowired
    private TrackRepository tracks;

    @org.springframework.beans.factory.annotation.Autowired
    private StatusRepository statuses;

    @org.springframework.beans.factory.annotation.Autowired
    private VehicleRepository vehicles;

    @org.springframework.beans.factory.annotation.Autowired
    private VehicleService vehicleService;

    @org.springframework.beans.factory.annotation.Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService workers;

    @BeforeEach
    void setUpDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, transactionManager);
        TENANT_ID = TestSchema.defaultTenantId(jdbc);
        jdbc.sql("DROP TRIGGER IF EXISTS fail_status_write").update();
        jdbc.sql("DELETE FROM alarm_condition_state").update();
        jdbc.sql("DELETE FROM geofence_presence").update();
        jdbc.sql("DELETE FROM geofence_vehicle").update();
        jdbc.sql("DELETE FROM alarm_event").update();
        jdbc.sql("DELETE FROM geofence").update();
        jdbc.sql("DELETE FROM vehicle_daily_stat").update();
        jdbc.sql("DELETE FROM track_point").update();
        jdbc.sql("DELETE FROM device_status").update();
        jdbc.sql("DELETE FROM processed_event").update();
        jdbc.sql("DELETE FROM fleet_vehicle").update();
        jdbc.sql("DELETE FROM fleet").update();
        jdbc.sql("DELETE FROM vehicle").update();
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        if (workers != null) {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void commitsCompleteLocationOnceAndRejectsMissingIdBeforeWriting() {
        MessageEnvelope envelope = location("event-1", " 00123 ", "2026-08-11T10:00:00");

        IngestionResult first = ingestion.ingest(envelope);
        IngestionResult duplicate = ingestion.ingest(envelope);

        assertEquals("committed", first.result());
        assertEquals("located", first.outcome());
        assertNotNull(first.liveUpdate());
        assertEquals("00123", first.liveUpdate().get("deviceId"));
        assertThrows(UnsupportedOperationException.class,
                () -> first.liveUpdate().put("deviceId", "changed"));
        @SuppressWarnings("unchecked")
        List<String> alarms = (List<String>) first.liveUpdate().get("alarms");
        assertThrows(UnsupportedOperationException.class, () -> alarms.add("changed"));
        assertEquals("duplicate", duplicate.result());
        assertEquals(1, count("processed_event"));
        assertEquals(1, count("track_point"));
        assertEquals(1, count("device_status"));

        MessageEnvelope missingId = new MessageEnvelope(
                " ", "00123", 0x0002L, 2, "2019", "2026-08-11T10:01:00Z",
                "gateway-a", "heartbeat", Map.of());
        assertThrows(InvalidEnvelopeException.class, () -> ingestion.ingest(missingId));
        assertEquals(1, count("processed_event"));
        assertEquals(1, count("track_point"));
        assertEquals(1, count("device_status"));
    }

    @Test
    void rollsBackEveryProjectionAndAllowsSameEventToRetry() {
        jdbc.sql("""
                CREATE TRIGGER fail_status_write
                BEFORE INSERT ON device_status
                BEGIN
                    SELECT RAISE(ABORT, 'forced status failure');
                END
                """).update();

        MessageEnvelope envelope = location("event-retry", "13900000001", "2026-08-11T11:00:00");
        assertThrows(RuntimeException.class, () -> ingestion.ingest(envelope));
        assertEquals(0, count("processed_event"));
        assertEquals(0, count("track_point"));
        assertEquals(0, count("device_status"));

        jdbc.sql("DROP TRIGGER fail_status_write").update();
        IngestionResult retry = ingestion.ingest(envelope);

        assertEquals("committed", retry.result());
        assertEquals(1, count("processed_event"));
        assertEquals(1, count("track_point"));
        assertEquals(1, count("device_status"));
    }

    @Test
    void concurrentDuplicateDeliveryHasExactlyOneCommitter() throws Exception {
        int concurrency = 6;
        workers = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        MessageEnvelope envelope = location("event-concurrent", "13900000002", "2026-08-11T12:00:00");
        List<Future<IngestionResult>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return ingestion.ingest(envelope);
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        List<IngestionResult> results = new ArrayList<>();
        for (Future<IngestionResult> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }

        assertEquals(1, results.stream().filter(result -> "committed".equals(result.result())).count());
        assertEquals(concurrency - 1L,
                results.stream().filter(result -> "duplicate".equals(result.result())).count());
        assertEquals(1, count("processed_event"));
        assertEquals(1, count("track_point"));
        assertEquals(1, count("device_status"));
    }

    @Test
    void keepsLeadingZeroDeviceKeysIsolatedAcrossCrudStatusAndTracks() {
        AuthorizedPrincipal platform = TestPrincipals.platform();
        VehicleController controller = new VehicleController(vehicleService);
        controller.create(vehicle(" 00123 ", "京A00123"), platform);
        controller.create(vehicle("123", "京A00124"), platform);

        ingestion.ingest(location("event-zero", "00123", "2026-08-11T13:00:00"));
        ingestion.ingest(location("event-plain", "123", "2026-08-11T14:00:00"));

        Map<String, LiveStatus> liveById = statuses.findAllLive(SCOPE).stream()
                .collect(Collectors.toMap(LiveStatus::deviceId, status -> status));
        assertEquals("京A00123", liveById.get("00123").plateNo());
        assertEquals("京A00124", liveById.get("123").plateNo());
        assertEquals(1, tracks.findRange("00123", START, END, 10, SCOPE).size());
        assertEquals(1, tracks.findRange("123", START, END, 10, SCOPE).size());

        controller.update(" 00123 ", vehicle("ignored", "京A00999"), platform);
        assertEquals("京A00999", controller.get("00123", SCOPE).data().plateNo());
        assertEquals("京A00124", controller.get("123", SCOPE).data().plateNo());

        controller.delete(" 123 ", platform);
        assertTrue(vehicles.findById("00123", SCOPE).isPresent());
        assertFalse(vehicles.findById("123", SCOPE).isPresent());
        assertEquals(1, tracks.countByDevice("00123"));
        assertEquals(1, tracks.countByDevice("123"));
    }

    private int count(String table) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    private static VehicleService.VehicleRequest vehicle(String deviceId, String plateNo) {
        return new VehicleService.VehicleRequest(
                deviceId, plateNo, "蓝色", "测试车辆", 1, null, TENANT_ID, null);
    }

    private static MessageEnvelope location(String eventId, String deviceId, String deviceTime) {
        Map<String, Object> payload = Map.of(
                "latitude", 39.9042D,
                "longitude", 116.4074D,
                "deviceTime", deviceTime,
                "speedKph", 36.5D,
                "direction", 90,
                "altitude", 48,
                "statusFlags", Map.of("positioned", true, "accOn", true),
                "alarmFlags", Map.of("overspeed", false),
                "attributes", Map.of("0x1", 1234));
        return new MessageEnvelope(
                eventId,
                deviceId,
                MessageEnvelope.LOCATION_REPORT,
                1,
                "2019",
                deviceTime + "Z",
                "gateway-a",
                "location",
                payload);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {

        @Bean
        DataSource dataSource() throws IOException {
            Path database = Files.createTempFile("jt-console-ingestion-", ".db");
            database.toFile().deleteOnExit();
            SQLiteDataSource dataSource = new SQLiteDataSource();
            String path = database.toAbsolutePath().toString().replace('\\', '/');
            dataSource.setUrl("jdbc:sqlite:" + path + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL");
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        EventRepository eventRepository(JdbcClient jdbc) {
            return new EventRepository(jdbc);
        }

        @Bean
        TrackRepository trackRepository(JdbcClient jdbc) {
            return new TrackRepository(jdbc);
        }

        @Bean
        StatusRepository statusRepository(JdbcClient jdbc) {
            return new StatusRepository(jdbc);
        }

        @Bean
        DeviceAttributeRepository deviceAttributeRepository(JdbcClient jdbc) {
            return new DeviceAttributeRepository(jdbc);
        }

        @Bean
        MediaRepository mediaRepository(JdbcClient jdbc) {
            return new MediaRepository(jdbc);
        }

        @Bean
        VehicleRepository vehicleRepository(JdbcClient jdbc) {
            return new VehicleRepository(jdbc);
        }

        @Bean
        TenantRepository tenantRepository(JdbcClient jdbc) {
            return new TenantRepository(jdbc);
        }

        @Bean
        PlanRepository planRepository(JdbcClient jdbc) {
            return new PlanRepository(jdbc);
        }

        /** 本测试的车辆都不挂部门，组织校验与广播缓存用替身即可。 */
        @Bean
        VehicleService vehicleService(
                VehicleRepository vehicles, TenantRepository tenants, PlanRepository plans) {
            return new VehicleService(vehicles, tenants, plans,
                    org.mockito.Mockito.mock(OrganizationService.class),
                    org.mockito.Mockito.mock(DeviceOwnershipCache.class));
        }

        @Bean
        AlarmRepository alarmRepository(JdbcClient jdbc) {
            return new AlarmRepository(jdbc);
        }

        @Bean
        AlarmRuleRepository alarmRuleRepository(JdbcClient jdbc) {
            return new AlarmRuleRepository(jdbc);
        }

        @Bean
        DriverRepository driverRepository(JdbcClient jdbc) {
            return new DriverRepository(jdbc);
        }

        @Bean
        DailyStatRepository dailyStatRepository(JdbcClient jdbc) {
            return new DailyStatRepository(jdbc);
        }

        @Bean
        GeofenceRepository geofenceRepository(JdbcClient jdbc) {
            return new GeofenceRepository(jdbc);
        }

        @Bean
        ConsoleProperties consoleProperties() {
            return new ConsoleProperties();
        }

        @Bean
        BusinessDateService businessDateService(ConsoleProperties properties) {
            return new BusinessDateService(properties);
        }

        @Bean
        AlarmService alarmService(
                AlarmRepository alarms, DailyStatRepository stats, BusinessDateService dates) {
            return new AlarmService(alarms, stats, dates);
        }

        @Bean
        DailyStatService dailyStatService(DailyStatRepository stats, BusinessDateService dates) {
            return new DailyStatService(stats, dates);
        }

        @Bean
        GeofenceService geofenceService(
                GeofenceRepository geofences, VehicleRepository vehicles,
                AlarmRepository alarms, AlarmService alarmService) {
            return new GeofenceService(geofences, vehicles, alarms, alarmService);
        }

        @Bean
        RuleService ruleService(
                AlarmRuleRepository rules, VehicleRepository vehicles,
                AlarmService alarms, AlarmRepository alarmConditions) {
            return new RuleService(rules, vehicles, alarms, alarmConditions);
        }

        @Bean
        LocationProjection locationProjection(
                TrackRepository tracks, StatusRepository statuses, DailyStatService stats,
                ObjectMapper objectMapper) {
            return new LocationProjection(tracks, statuses, stats, objectMapper);
        }

        @Bean
        BatchLocationService batchLocationService(
                LocationProjection projection, StatusRepository statuses,
                ConsoleProperties properties) {
            return new BatchLocationService(projection, statuses, properties);
        }

        @Bean
        LocationService locationService(
                StatusRepository statuses, LocationProjection projection,
                BatchLocationService batches, AlarmService alarms, GeofenceService geofences,
                RuleService rules, DeviceAttributeRepository attributes) {
            return new LocationService(statuses, projection, batches, alarms, geofences, rules, attributes);
        }

        @Bean
        EventIngestionService eventIngestionService(
                EventRepository events, LocationService locations, MediaRepository media,
                DriverRepository drivers) {
            return new EventIngestionService(events, locations, new MediaIngestionService(media),
                    new DriverIdentityIngestionService(drivers));
        }
    }
}
