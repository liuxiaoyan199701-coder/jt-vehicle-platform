package io.github.jtconsole.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jtconsole.domain.AlarmEvent;
import io.github.jtconsole.domain.AlarmLevel;
import io.github.jtconsole.domain.AlarmSource;
import io.github.jtconsole.domain.Geofence;
import io.github.jtconsole.domain.GeofenceShape;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.domain.VehicleDailyStat;
import io.github.jtconsole.ingest.EventIngestionService;
import io.github.jtconsole.ingest.MessageEnvelope;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.AlarmRepository.AlarmFilter;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.repository.GeofenceRepository;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.SessionTokenService;
import io.github.jtconsole.support.TestPrincipals;
import io.github.jtconsole.support.TestSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class OperationsIntegrationTest {

    private static final String DATABASE_URL = "jdbc:sqlite:file:operations-" + UUID.randomUUID()
            + "?mode=memory&cache=shared";
    private static final String DEVICE = "00123";
    /** 平台视角：跨租户可见，含未建档设备。 */
    private static final DataScope SCOPE = DataScope.platform();
    private static final AuthorizedPrincipal PLATFORM = TestPrincipals.platform();
    private long TENANT_ID;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private EventIngestionService ingestion;
    @Autowired private AlarmRepository alarms;
    @Autowired private GeofenceRepository geofenceRepository;
    @Autowired private GeofenceService geofences;
    @Autowired private DailyStatRepository dailyStats;
    @Autowired private DailyStatService dailyStatService;
    @Autowired private VehicleRepository vehicles;
    @Autowired private SessionTokenService tokens;

    private MockMvc mvc;
    private String bearer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("jt.console.security.ingest-key",
                () -> "operations-integration-ingest-key-32-bytes");
        registry.add("jt.console.operations.zone-id", () -> "Asia/Shanghai");
    }

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        bearer = "Bearer " + tokens.issue(1L, "admin", null).token();
        TENANT_ID = TestSchema.defaultTenantId(jdbc);
        jdbc.sql("DROP TRIGGER IF EXISTS fail_alarm_write").update();
        for (String table : List.of(
                "alarm_condition_state", "geofence_presence", "geofence_vehicle",
                "alarm_event", "geofence", "vehicle_daily_stat", "track_point",
                "device_status", "processed_event", "fleet_vehicle", "fleet", "vehicle")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    void protocolAlarmLifecycleHandlesMissingPartialReservedAndUnpositionedReports() {
        ingestion.ingest(location("p-1", DEVICE, "2026-08-11T10:00:00",
                0, 0, 0, null, false, Map.of("overspeed", true, "reserved15", true), true));
        assertThat(count("alarm_event")).isEqualTo(1);
        assertThat(count("track_point")).isZero();
        assertThat(alarms.countActive(DEVICE)).isEqualTo(1);
        assertThat(alarms.recent(10, SCOPE).getFirst().type()).isEqualTo("overspeed");

        ingestion.ingest(location("p-2", DEVICE, "2026-08-11T10:01:00",
                39.9, 116.4, 20, null, true, Map.of(), false));
        ingestion.ingest(location("p-3", DEVICE, "2026-08-11T10:02:00",
                39.9, 116.4, 20, null, true, Map.of("emergency", false), true));
        assertThat(alarms.countActive(DEVICE)).isEqualTo(1);
        assertThat(count("alarm_event")).isEqualTo(1);

        ingestion.ingest(location("p-4", DEVICE, "2026-08-11T10:03:00",
                39.9, 116.4, 20, null, true, Map.of("overspeed", false), true));
        assertThat(alarms.countActive(DEVICE)).isZero();
        var retriggered = ingestion.ingest(location("p-5", DEVICE, "2026-08-11T10:04:00",
                39.9, 116.4, 20, null, true, Map.of("overspeed", true), true));
        assertThat(count("alarm_event")).isEqualTo(2);
        assertThat(retriggered.liveUpdate()).containsEntry("activeAlarmCount", 1);

        ingestion.ingest(location("p-other", "123", "2026-08-11T10:05:00",
                39.9, 116.4, 20, null, true, Map.of("overspeed", true), true));
        assertThat(alarms.search(new AlarmFilter(null, null, null, DEVICE,
                null, null, null, null, 1, 20), SCOPE).total()).isEqualTo(2);
    }

    @Test
    void geofenceTransitionsDeduplicateSpeedAndManagementChangesAreAtomic() {
        createVehicle(DEVICE);
        Geofence created = geofences.create(PLATFORM, fence(null, "仓库", List.of(DEVICE), true, 30.0));
        assertThat(created.vehicleIds()).containsExactly(DEVICE);

        ingestion.ingest(location("g-1", DEVICE, "2026-08-11T11:00:00",
                39.90, 116.30, 10, 100.0, true, Map.of(), true));
        assertThat(count("alarm_event")).isZero();
        ingestion.ingest(location("g-2", DEVICE, "2026-08-11T11:01:00",
                39.90, 116.40, 20, 100.5, true, Map.of(), true));
        assertThat(alarmTypes()).containsExactly("geofenceEnter");

        ingestion.ingest(location("g-3", DEVICE, "2026-08-11T11:02:00",
                39.90, 116.40, 40, 100.6, true, Map.of(), true));
        ingestion.ingest(location("g-4", DEVICE, "2026-08-11T11:03:00",
                39.90, 116.40, 45, 100.7, true, Map.of(), true));
        assertThat(alarmTypes()).containsExactlyInAnyOrder("geofenceEnter", "geofenceOverspeed");

        assertThatThrownBy(() -> geofences.update(created.id(),
                fence(created.id(), "不应保存", List.of("unknown"), true, 30.0), SCOPE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(geofences.findById(created.id(), SCOPE).orElseThrow().name()).isEqualTo("仓库");
        assertThat(geofences.findById(created.id(), SCOPE).orElseThrow().vehicleIds())
                .containsExactly(DEVICE);

        geofences.setEnabled(created.id(), false, SCOPE);
        assertThat(alarms.countActive(DEVICE)).isZero();
        ingestion.ingest(location("g-5", DEVICE, "2026-08-11T11:04:00",
                39.90, 116.30, 50, 100.8, true, Map.of(), true));
        assertThat(count("alarm_event")).isEqualTo(2);
        geofences.delete(created.id(), SCOPE);
        assertThat(count("alarm_event")).isEqualTo(2);
        assertThat(count("geofence_vehicle")).isZero();
        assertThat(count("geofence_presence")).isZero();
        assertThat(count("alarm_condition_state")).isZero();
    }

    @Test
    void equivalentGeofenceManagementDoesNotRestartAnActiveOverspeedCondition() {
        createVehicle(DEVICE);
        Geofence created = geofences.create(PLATFORM, fence(null, "等价配置", List.of(DEVICE), true, 30.0));

        ingestion.ingest(location("same-1", DEVICE, "2026-08-11T11:10:00",
                39.90, 116.40, 40.0, 100.0, true, Map.of(), true));
        long afterInitialReport = alarmTotal(AlarmSource.GEOFENCE, "geofenceOverspeed");

        geofences.update(created.id(),
                fence(created.id(), "等价配置", List.of(DEVICE), true, 30.0), SCOPE);
        ingestion.ingest(location("same-2", DEVICE, "2026-08-11T11:11:00",
                39.90, 116.40, 41.0, 100.1, true, Map.of(), true));
        long afterEquivalentUpdate = alarmTotal(AlarmSource.GEOFENCE, "geofenceOverspeed");

        geofences.setEnabled(created.id(), true, SCOPE);
        ingestion.ingest(location("same-3", DEVICE, "2026-08-11T11:12:00",
                39.90, 116.40, 42.0, 100.2, true, Map.of(), true));
        long afterRepeatedEnable = alarmTotal(AlarmSource.GEOFENCE, "geofenceOverspeed");

        geofences.replaceVehicles(created.id(), List.of(DEVICE), SCOPE);
        ingestion.ingest(location("same-4", DEVICE, "2026-08-11T11:13:00",
                39.90, 116.40, 43.0, 100.3, true, Map.of(), true));
        long afterEquivalentAssignment = alarmTotal(AlarmSource.GEOFENCE, "geofenceOverspeed");

        assertSoftly(softly -> {
            softly.assertThat(afterInitialReport).isEqualTo(1);
            softly.assertThat(afterEquivalentUpdate).isEqualTo(1);
            softly.assertThat(afterRepeatedEnable).isEqualTo(1);
            softly.assertThat(afterEquivalentAssignment).isEqualTo(1);
            softly.assertThat(alarms.findCondition(
                            DEVICE, AlarmSource.GEOFENCE, "overspeed:" + created.id())
                    .map(AlarmRepository.ConditionState::active)).contains(true);
        });
    }

    @Test
    void missingSpeedInsideGeofenceDoesNotClearActiveOverspeed() {
        createVehicle(DEVICE);
        Geofence created = geofences.create(PLATFORM, fence(null, "限速围栏", List.of(DEVICE), true, 30.0));

        ingestion.ingest(location("speed-1", DEVICE, "2026-08-11T11:20:00",
                39.90, 116.40, 40.0, 200.0, true, Map.of(), true));
        ingestion.ingest(location("speed-2", DEVICE, "2026-08-11T11:21:00",
                39.90, 116.40, null, 200.1, true, Map.of(), true));
        boolean activeAfterMissingSpeed = alarms.findCondition(
                        DEVICE, AlarmSource.GEOFENCE, "overspeed:" + created.id())
                .map(AlarmRepository.ConditionState::active).orElse(false);
        ingestion.ingest(location("speed-3", DEVICE, "2026-08-11T11:22:00",
                39.90, 116.40, 41.0, 200.2, true, Map.of(), true));

        assertSoftly(softly -> {
            softly.assertThat(activeAfterMissingSpeed).isTrue();
            softly.assertThat(alarmTotal(AlarmSource.GEOFENCE, "geofenceOverspeed"))
                    .isEqualTo(1);
        });
    }

    @Test
    void deletingVehicleProfilePreservesActiveProtocolCondition() {
        createVehicle(DEVICE);
        ingestion.ingest(location("delete-1", DEVICE, "2026-08-11T11:30:00",
                39.90, 116.40, 20.0, 300.0, true, Map.of("overspeed", true), true));

        assertThat(vehicles.delete(DEVICE)).isEqualTo(1);
        boolean activeAfterProfileDeletion = alarms.findCondition(
                        DEVICE, AlarmSource.PROTOCOL, "overspeed")
                .map(AlarmRepository.ConditionState::active).orElse(false);
        ingestion.ingest(location("delete-2", DEVICE, "2026-08-11T11:31:00",
                39.90, 116.40, 20.0, 300.1, true, Map.of("overspeed", true), true));

        assertSoftly(softly -> {
            softly.assertThat(activeAfterProfileDeletion).isTrue();
            softly.assertThat(alarmTotal(AlarmSource.PROTOCOL, "overspeed")).isEqualTo(1);
            softly.assertThat(alarms.findCondition(DEVICE, AlarmSource.PROTOCOL, "overspeed")
                    .map(AlarmRepository.ConditionState::active)).contains(true);
        });
    }

    @Test
    void lateLocationsDoNotRewindLatestStatusGeofenceOrProtocolState() {
        createVehicle(DEVICE);
        Geofence created = geofences.create(PLATFORM, fence(null, "时序围栏", List.of(DEVICE), true, null));

        ingestion.ingest(locationAt("late-new", DEVICE, "2026-08-11T10:10:00",
                "2026-08-11T02:10:00Z", 39.90, 116.40, 20.0, 401.0,
                true, Map.of("overspeed", true), true));
        AlarmEvent original = alarm(AlarmSource.PROTOCOL, "overspeed");

        ingestion.ingest(locationAt("late-old-active", DEVICE, "2026-08-11T10:05:00",
                "2026-08-11T02:05:00Z", 39.90, 116.30, 10.0, 400.5,
                true, Map.of("overspeed", true), true));
        ingestion.ingest(locationAt("late-old-cleared", DEVICE, "2026-08-11T10:06:00",
                "2026-08-11T02:06:00Z", 39.90, 116.30, 10.0, 400.6,
                true, Map.of("overspeed", false), true));

        String latestDeviceTime = jdbc.sql(
                        "SELECT device_time FROM device_status WHERE device_id = ?")
                .param(DEVICE).query(String.class).single();
        String latestSeenAt = jdbc.sql(
                        "SELECT last_seen_at FROM device_status WHERE device_id = ?")
                .param(DEVICE).query(String.class).single();
        Double latestLng = jdbc.sql("SELECT lng FROM device_status WHERE device_id = ?")
                .param(DEVICE).query(Double.class).single();
        AlarmEvent afterLateReports = alarm(AlarmSource.PROTOCOL, "overspeed");

        assertSoftly(softly -> {
            softly.assertThat(latestDeviceTime).isEqualTo("2026-08-11T10:10:00.000+08:00");
            softly.assertThat(latestSeenAt).isEqualTo("2026-08-11T10:10:00.000+08:00");
            softly.assertThat(latestLng).isEqualTo(116.40);
            softly.assertThat(geofenceRepository.presence(created.id(), DEVICE)).contains(true);
            softly.assertThat(alarms.findCondition(DEVICE, AlarmSource.PROTOCOL, "overspeed")
                    .map(AlarmRepository.ConditionState::active)).contains(true);
            softly.assertThat(afterLateReports.id()).isEqualTo(original.id());
            softly.assertThat(afterLateReports.lastOccurredAt()).isEqualTo(original.lastOccurredAt());
            softly.assertThat(alarmTotal(AlarmSource.PROTOCOL, "overspeed")).isEqualTo(1);
        });
    }

    @Test
    void dailyStatisticsUseMileageFallbackBusinessZoneAndIgnoreOutOfOrderOrJumps() {
        dailyStatService.record(DEVICE, "2026-08-11T10:00:00", "2026-08-11T02:00:00Z",
                39.9, 116.4, 10.0, 100.0);
        dailyStatService.record(DEVICE, "2026-08-11T10:01:00", "2026-08-11T02:01:00Z",
                39.9001, 116.4001, 20.0, 101.5);
        dailyStatService.record(DEVICE, "2026-08-11T10:02:00", "2026-08-11T02:02:00Z",
                39.9002, 116.4002, 20.0, 1.0);
        VehicleDailyStat beforeOutOfOrder = dailyStats.find(DEVICE, "2026-08-11").orElseThrow();
        assertThat(beforeOutOfOrder.distanceKm()).isBetween(1.5, 1.6);

        dailyStatService.record(DEVICE, "2026-08-11T09:00:00", "2026-08-11T03:00:00Z",
                0, 0, 200.0, null);
        dailyStatService.record(DEVICE, "2026-08-11T10:03:00", "2026-08-11T03:01:00Z",
                0, 0, 200.0, null);
        VehicleDailyStat after = dailyStats.find(DEVICE, "2026-08-11").orElseThrow();
        assertThat(after.distanceKm()).isEqualTo(beforeOutOfOrder.distanceKm());
        assertThat(after.lastDeviceTime()).isEqualTo("2026-08-11T10:03:00");
        assertThat(after.pointCount()).isEqualTo(5);
        assertThat(after.maxSpeedKph()).isEqualTo(200.0);

        dailyStatService.record("zone-device", "invalid", "2026-08-11T16:30:00Z",
                39.9, 116.4, 0.0, null);
        assertThat(dailyStats.find("zone-device", "2026-08-12")).isPresent();
    }

    @Test
    void missingOrInvalidDeviceTimeDoesNotDestroyDailyStatisticsCursor() {
        String deviceId = "cursor-device";
        dailyStatService.record(deviceId, "2026-08-11T10:00:00", "2026-08-11T02:00:00Z",
                39.9000, 116.4000, 10.0, 100.0);
        dailyStatService.record(deviceId, "2026-08-11T10:10:00", "2026-08-11T02:10:00Z",
                39.9001, 116.4001, 10.0, 101.0);

        dailyStatService.record(deviceId, null, "2026-08-11T02:11:00Z",
                40.5000, 117.0000, 10.0, 500.0);
        VehicleDailyStat afterMissingTime = dailyStats.find(deviceId, "2026-08-11").orElseThrow();
        dailyStatService.record(deviceId, "not-a-device-time", "2026-08-11T02:12:00Z",
                40.5000, 117.0000, 10.0, 600.0);
        VehicleDailyStat afterInvalidTime = dailyStats.find(deviceId, "2026-08-11").orElseThrow();
        dailyStatService.record(deviceId, "2026-08-11T10:20:00", "2026-08-11T02:20:00Z",
                39.9002, 116.4002, 10.0, 102.0);
        VehicleDailyStat afterNextValidTime = dailyStats.find(deviceId, "2026-08-11").orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(afterMissingTime.lastDeviceTime()).isEqualTo("2026-08-11T10:10:00");
            softly.assertThat(afterMissingTime.distanceKm()).isEqualTo(1.0);
            softly.assertThat(afterInvalidTime.lastDeviceTime()).isEqualTo("2026-08-11T10:10:00");
            softly.assertThat(afterInvalidTime.distanceKm()).isEqualTo(1.0);
            softly.assertThat(afterNextValidTime.lastDeviceTime()).isEqualTo("2026-08-11T10:20:00");
            softly.assertThat(afterNextValidTime.lastMileage()).isEqualTo(102.0);
            softly.assertThat(afterNextValidTime.distanceKm()).isEqualTo(2.0);
            softly.assertThat(afterNextValidTime.pointCount()).isEqualTo(5);
        });
    }

    @Test
    void alarmFailureRollsBackAllDerivedStateAndSameEventCanRetry() {
        createVehicle(DEVICE);
        Geofence fence = geofences.create(PLATFORM, fence(null, "回滚围栏", List.of(DEVICE), true, null));
        ingestion.ingest(location("r-base", DEVICE, "2026-08-11T12:00:00",
                39.90, 116.30, 10, 10.0, true, Map.of(), true));
        int tracksBefore = count("track_point");
        int eventsBefore = count("processed_event");
        VehicleDailyStat statsBefore = dailyStats.find(DEVICE, "2026-08-11").orElseThrow();
        jdbc.sql("""
                CREATE TRIGGER fail_alarm_write BEFORE INSERT ON alarm_event
                BEGIN SELECT RAISE(ABORT, 'forced alarm failure'); END
                """).update();

        MessageEnvelope crossing = location("r-cross", DEVICE, "2026-08-11T12:01:00",
                39.90, 116.40, 10, 10.5, true, Map.of(), true);
        assertThatThrownBy(() -> ingestion.ingest(crossing)).isInstanceOf(RuntimeException.class);
        assertThat(count("track_point")).isEqualTo(tracksBefore);
        assertThat(count("processed_event")).isEqualTo(eventsBefore);
        assertThat(dailyStats.find(DEVICE, "2026-08-11").orElseThrow()).isEqualTo(statsBefore);
        assertThat(geofenceRepository.presence(fence.id(), DEVICE)).contains(false);

        jdbc.sql("DROP TRIGGER fail_alarm_write").update();
        ingestion.ingest(crossing);
        assertThat(count("track_point")).isEqualTo(tracksBefore + 1);
        assertThat(alarmTypes()).contains("geofenceEnter");
    }

    @Test
    void protectedOperationsApisReturnStableEmptyAndBusinessErrorContracts() throws Exception {
        mvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/alarms").header(HttpHeaders.AUTHORIZATION, bearer)
                        .param("status", "not-a-status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("4000"));
        mvc.perform(get("/api/dashboard/overview").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.summary.fleetVehicles").value(0))
                .andExpect(jsonPath("$.data.summary.online").value(0))
                .andExpect(jsonPath("$.data.summary.todayDistanceKm").value(0.0))
                .andExpect(jsonPath("$.data.dailyTrend.length()").value(7))
                .andExpect(jsonPath("$.data.alarmLevels.length()").value(4))
                .andExpect(jsonPath("$.data.recentAlarms.length()").value(0));

        createVehicle(DEVICE);
        mvc.perform(get("/api/vehicles/{deviceId}/profile", DEVICE)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.vehicle.deviceId").value(DEVICE))
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.today.distanceKm").value(0.0));

        ingestion.ingest(location("api-alarm", DEVICE, "2026-08-11T13:00:00",
                39.9, 116.4, 10, null, true, Map.of("emergency", true), true));
        AlarmEvent alarm = alarms.recent(1, SCOPE).getFirst();
        mvc.perform(post("/api/alarms/{id}/acknowledge", alarm.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已核实\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.acknowledgedBy").value("admin"));
        mvc.perform(post("/api/alarms/{id}/close", alarm.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已处理\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        mvc.perform(post("/api/alarms/{id}/close", alarm.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"重复\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("4009"));
        mvc.perform(get("/api/alarms/{id}", 999999)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("4004"));
    }

    @Test
    void geofenceHttpMutationAcceptsFrontendContractAndPersistsAssignmentsAtomically() throws Exception {
        createVehicle(DEVICE);
        // 平台管理员必须显式指定围栏归属租户，否则请求被拒。
        String body = """
                {"name":"HTTP 围栏","centerGcjLat":39.90,"centerGcjLng":116.40,
                 "radiusMeters":500,"color":"#18A058","enabled":true,
                 "alertOnEnter":true,"alertOnExit":true,"speedLimitKph":60,
                 "vehicleIds":["00123"],"tenantId":%d}
                """.formatted(TENANT_ID);
        String response = mvc.perform(post("/api/geofences")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.vehicleIds[0]").value(DEVICE))
                .andReturn().getResponse().getContentAsString();
        long id = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response).path("data").path("id").asLong();

        mvc.perform(put("/api/geofences/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("HTTP 围栏", "HTTP 围栏更新")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.name").value("HTTP 围栏更新"))
                .andExpect(jsonPath("$.data.assignedVehicleCount").value(1));
    }

    private void createVehicle(String deviceId) {
        vehicles.insert(new Vehicle(deviceId, "京A" + deviceId, "蓝色", "测试车", 1,
                null, TENANT_ID, null, null, null));
    }

    private Geofence fence(
            Long id, String name, List<String> deviceIds, boolean enabled, Double speedLimit) {
        return new Geofence(id, name, 39.90, 116.40, 1000,
                GeofenceShape.CIRCLE, List.of(),
                "#1677FF", enabled, true, true, speedLimit,
                deviceIds, deviceIds.size(), TENANT_ID, null, null);
    }

    private List<String> alarmTypes() {
        return alarms.recent(100, SCOPE).stream().map(AlarmEvent::type).toList();
    }

    private long alarmTotal(AlarmSource source, String type) {
        return alarms.search(new AlarmFilter(null, null, source, DEVICE,
                type, null, null, null, 1, 20), SCOPE).total();
    }

    private AlarmEvent alarm(AlarmSource source, String type) {
        return alarms.search(new AlarmFilter(null, null, source, DEVICE,
                type, null, null, null, 1, 20), SCOPE).items().getFirst();
    }

    private int count(String table) {
        Integer value = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
        return value == null ? 0 : value;
    }

    private static MessageEnvelope location(
            String eventId,
            String deviceId,
            String deviceTime,
            double lat,
            double lng,
            Number speed,
            Double mileage,
            boolean positioned,
            Map<String, Object> alarmFlags,
            boolean includeAlarmFlags) {
        return locationAt(eventId, deviceId, deviceTime, deviceTime + "Z", lat, lng,
                speed, mileage, positioned, alarmFlags, includeAlarmFlags);
    }

    private static MessageEnvelope locationAt(
            String eventId,
            String deviceId,
            String deviceTime,
            String receivedAt,
            double lat,
            double lng,
            Number speed,
            Double mileage,
            boolean positioned,
            Map<String, Object> alarmFlags,
            boolean includeAlarmFlags) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("latitude", lat);
        payload.put("longitude", lng);
        payload.put("deviceTime", deviceTime);
        payload.put("speedKph", speed);
        payload.put("direction", 90);
        payload.put("altitude", 20);
        payload.put("statusFlags", Map.of("positioned", positioned, "accOn", true));
        if (includeAlarmFlags) payload.put("alarmFlags", alarmFlags);
        payload.put("attributes", mileage == null ? Map.of() : Map.of("0x1", mileage * 10));
        return new MessageEnvelope(eventId, deviceId, MessageEnvelope.LOCATION_REPORT, 1,
                "2019", receivedAt, "gateway-a", "location", payload);
    }
}
