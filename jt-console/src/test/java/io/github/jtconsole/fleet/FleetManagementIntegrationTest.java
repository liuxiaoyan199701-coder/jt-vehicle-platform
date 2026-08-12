package io.github.jtconsole.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.security.SessionTokenService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class FleetManagementIntegrationTest {

    private static final String DATABASE_URL = "jdbc:sqlite:file:fleet-management-"
            + UUID.randomUUID() + "?mode=memory&cache=shared";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private VehicleRepository vehicles;
    @Autowired private SessionTokenService tokens;
    @Autowired private BusinessDateService businessDates;
    @Autowired private ObjectMapper json;

    private MockMvc mvc;
    private String bearer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("jt.console.operations.zone-id", () -> "Asia/Shanghai");
    }

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        bearer = "Bearer " + tokens.issue("admin").token();
        for (String table : List.of(
                "alarm_condition_state", "geofence_presence", "geofence_vehicle",
                "alarm_event", "geofence", "vehicle_daily_stat", "track_point",
                "device_status", "processed_event", "fleet_vehicle", "fleet", "vehicle")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    void authenticatedCrudSearchValidationAndDeleteContractsAreStable() throws Exception {
        long alpha = createFleet("  F-001  ", "  Alpha Fleet  ",
                "  Alice  ", "  13800000000  ", "  primary  ");
        long beta = createFleet("F-002", "Beta Operations", "Bob", "13900000000", null);

        mvc.perform(get("/api/fleets/{id}", alpha).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.fleet.code").value("F-001"))
                .andExpect(jsonPath("$.data.fleet.name").value("Alpha Fleet"))
                .andExpect(jsonPath("$.data.fleet.manager").value("Alice"))
                .andExpect(jsonPath("$.data.fleet.contactPhone").value("13800000000"))
                .andExpect(jsonPath("$.data.fleet.remark").value("primary"))
                .andExpect(jsonPath("$.data.summary.totalVehicles").value(0))
                .andExpect(jsonPath("$.data.summary.todayDistanceKm").value(0.0))
                .andExpect(jsonPath("$.data.members.length()").value(0));

        assertSearch("F-001", alpha);
        assertSearch("Alpha", alpha);
        assertSearch("Alice", alpha);
        assertSearch("1380000", alpha);
        mvc.perform(get("/api/fleets").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].fleet.id").value(alpha))
                .andExpect(jsonPath("$.data[1].fleet.id").value(beta));

        mvc.perform(put("/api/fleets/{id}", alpha)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fleetJson(" F-001 ", " Alpha Dispatch ",
                                " Alice Chen ", " 010-12345678 ", " updated ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.fleet.name").value("Alpha Dispatch"))
                .andExpect(jsonPath("$.data.fleet.manager").value("Alice Chen"));

        expectBusinessError(post("/api/fleets").content(
                fleetJson("F-001", "Duplicate", null, null, null)), "4009");
        expectBusinessError(put("/api/fleets/{id}", alpha).content(
                fleetJson("F-002", "Must Roll Back", null, null, null)), "4009");

        for (String invalid : List.of(
                fleetJson("   ", "Valid", null, null, null),
                fleetJson("F-003", "   ", null, null, null),
                fleetJson("C".repeat(33), "Valid", null, null, null),
                fleetJson("F-003", "N".repeat(101), null, null, null),
                fleetJson("F-003", "Valid", "M".repeat(51), null, null),
                fleetJson("F-003", "Valid", null, "P".repeat(51), null),
                fleetJson("F-003", "Valid", null, null, "R".repeat(501)))) {
            expectBusinessError(post("/api/fleets").content(invalid), "4000");
        }
        expectBusinessError(put("/api/fleets/{id}", alpha).content(
                fleetJson("F-001", "Invalid Update", null, null, "R".repeat(501))), "4000");
        mvc.perform(get("/api/fleets/{id}", alpha).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fleet.code").value("F-001"))
                .andExpect(jsonPath("$.data.fleet.name").value("Alpha Dispatch"))
                .andExpect(jsonPath("$.data.fleet.manager").value("Alice Chen"));
        assertThat(count("fleet")).isEqualTo(2);

        createVehicle("00123", "A-00123");
        setMembers(alpha, List.of("00123"));
        expectBusinessError(delete("/api/fleets/{id}", alpha), "4009");
        assertThat(memberIds(alpha)).containsExactly("00123");

        setMembers(alpha, List.of());
        mvc.perform(delete("/api/fleets/{id}", alpha)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(get("/api/fleets/{id}", alpha).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("4004"));
    }

    @Test
    void everyFleetEndpointRequiresAuthentication() throws Exception {
        String fleet = fleetJson("F-001", "Fleet", null, null, null);
        String members = json.writeValueAsString(Map.of("deviceIds", List.of("00123")));

        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/fleets"),
                get("/api/fleets/{id}", 1),
                post("/api/fleets").contentType(MediaType.APPLICATION_JSON).content(fleet),
                put("/api/fleets/{id}", 1).contentType(MediaType.APPLICATION_JSON).content(fleet),
                delete("/api/fleets/{id}", 1),
                put("/api/fleets/{id}/vehicles", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(members))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        assertThat(count("fleet")).isZero();
        assertThat(count("fleet_vehicle")).isZero();
    }

    @Test
    void completeMemberSetDeduplicatesIsIdempotentMatchesExactlyAndCanBeCleared()
            throws Exception {
        long paddedFleet = createFleet("F-PAD", "Padded IDs", null, null, null);
        long numericFleet = createFleet("F-NUM", "Numeric IDs", null, null, null);
        createVehicle("00123", "A-Padded");
        createVehicle("123", "B-Numeric");

        setMembers(paddedFleet, List.of("00123", "00123", "123"));
        assertThat(memberIds(paddedFleet)).containsExactlyInAnyOrder("00123", "123");
        assertThat(count("fleet_vehicle")).isEqualTo(2);

        setMembers(paddedFleet, List.of("123", "00123", "00123"));
        assertThat(memberIds(paddedFleet)).containsExactlyInAnyOrder("00123", "123");
        assertThat(count("fleet_vehicle")).isEqualTo(2);

        setMembers(numericFleet, List.of("123"));
        assertThat(memberIds(paddedFleet)).containsExactly("00123");
        assertThat(memberIds(numericFleet)).containsExactly("123");

        setMembers(numericFleet, List.of());
        assertThat(memberIds(numericFleet)).isEmpty();
        assertThat(memberIds(paddedFleet)).containsExactly("00123");
        assertThat(count("vehicle")).isEqualTo(2);
    }

    @Test
    void crossFleetTransferIsAtomicAndUnknownVehicleRollsBackEveryAssignment()
            throws Exception {
        long source = createFleet("F-SRC", "Source", null, null, null);
        long target = createFleet("F-DST", "Target", null, null, null);
        createVehicle("source-a", "A-Source");
        createVehicle("source-b", "B-Source");
        createVehicle("target-c", "C-Target");
        setMembers(source, List.of("source-a", "source-b"));
        setMembers(target, List.of("target-c"));

        expectBusinessError(put("/api/fleets/{id}/vehicles", target).content(
                json.writeValueAsString(Map.of(
                        "deviceIds", List.of("target-c", "source-a", "missing")))), "4004");
        assertThat(memberIds(source)).containsExactlyInAnyOrder("source-a", "source-b");
        assertThat(memberIds(target)).containsExactly("target-c");
        assertThat(count("fleet_vehicle")).isEqualTo(3);

        setMembers(target, List.of("target-c", "source-a"));
        assertThat(memberIds(source)).containsExactly("source-b");
        assertThat(memberIds(target)).containsExactlyInAnyOrder("target-c", "source-a");
        assertThat(count("fleet_vehicle")).isEqualTo(3);

        expectBusinessError(put("/api/fleets/{id}/vehicles", 999_999).content(
                json.writeValueAsString(Map.of("deviceIds", List.of("source-b")))), "4004");
        assertThat(memberIds(source)).containsExactly("source-b");
        assertThat(memberIds(target)).containsExactlyInAnyOrder("target-c", "source-a");
    }

    @Test
    void aggregatesMixedStatusWithoutAlarmFanoutAndMoveWithCurrentOwnership()
            throws Exception {
        long source = createFleet("F-STATS", "Statistics", null, null, null);
        long target = createFleet("F-NEW", "New Owner", null, null, null);
        createVehicle("moving", "A-Moving");
        createVehicle("idle", "B-Idle");
        createVehicle("offline", "C-Offline");
        setMembers(source, List.of("moving", "idle", "offline"));

        insertStatus("moving", true, 12.0, "2026-08-12T02:00:00Z");
        insertStatus("idle", true, 5.0, "2026-08-12T02:01:00Z");
        insertStatus("offline", false, 80.0, "2026-08-11T02:00:00Z");
        insertAlarm("moving", "OPEN", "open-1");
        insertAlarm("moving", "ACKNOWLEDGED", "open-2");
        insertAlarm("moving", "CLOSED", "closed");
        insertAlarm("idle", "OPEN", "idle-open");
        insertDistance("moving", 12.5);
        insertDistance("idle", 3.0);
        insertDistance("offline", 4.0);

        assertSummary(source, 3, 2, 1, 1, 1, 3, 19.5);
        MvcResult detail = fleetDetails(source);
        assertThat(memberValue(detail, "moving", "online", Boolean.class)).isTrue();
        assertThat(memberValue(detail, "moving", "speedKph", Number.class).doubleValue())
                .isEqualTo(12.0);
        assertThat(memberValue(detail, "moving", "openAlarmCount", Number.class).longValue())
                .isEqualTo(2);
        assertThat(memberValue(detail, "moving", "todayDistanceKm", Number.class).doubleValue())
                .isEqualTo(12.5);
        assertThat(memberValue(detail, "offline", "online", Boolean.class)).isFalse();

        setMembers(target, List.of("moving"));
        assertSummary(source, 2, 1, 0, 1, 1, 1, 7.0);
        assertSummary(target, 1, 1, 1, 0, 0, 2, 12.5);
        assertThat(count("alarm_event")).isEqualTo(4);
        assertThat(count("vehicle_daily_stat")).isEqualTo(3);
    }

    private long createFleet(
            String code, String name, String manager, String contactPhone, String remark)
            throws Exception {
        String response = mvc.perform(post("/api/fleets")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fleetJson(code, name, manager, contactPhone, remark)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.fleet.id")).longValue();
    }

    private String fleetJson(
            String code, String name, String manager, String contactPhone, String remark)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("manager", manager);
        body.put("contactPhone", contactPhone);
        body.put("remark", remark);
        return json.writeValueAsString(body);
    }

    private void assertSearch(String keyword, long expectedId) throws Exception {
        mvc.perform(get("/api/fleets")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fleet.id").value(expectedId));
    }

    private void expectBusinessError(MockHttpServletRequestBuilder request, String code)
            throws Exception {
        mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }

    private MvcResult setMembers(long fleetId, List<String> deviceIds) throws Exception {
        return mvc.perform(put("/api/fleets/{id}/vehicles", fleetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("deviceIds", deviceIds))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn();
    }

    private MvcResult fleetDetails(long fleetId) throws Exception {
        return mvc.perform(get("/api/fleets/{id}", fleetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn();
    }

    private List<String> memberIds(long fleetId) throws Exception {
        return JsonPath.read(fleetDetails(fleetId).getResponse().getContentAsString(),
                "$.data.members[*].vehicle.deviceId");
    }

    private <T> T memberValue(
            MvcResult result, String deviceId, String field, Class<T> type) throws Exception {
        List<T> values = JsonPath.read(result.getResponse().getContentAsString(),
                "$.data.members[?(@.vehicle.deviceId == '" + deviceId + "')]." + field);
        assertThat(values).hasSize(1);
        return type.cast(values.getFirst());
    }

    private void assertSummary(
            long fleetId,
            int total,
            int online,
            int moving,
            int idle,
            int offline,
            long openAlarms,
            double distance) throws Exception {
        mvc.perform(get("/api/fleets/{id}", fleetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalVehicles").value(total))
                .andExpect(jsonPath("$.data.summary.online").value(online))
                .andExpect(jsonPath("$.data.summary.moving").value(moving))
                .andExpect(jsonPath("$.data.summary.idle").value(idle))
                .andExpect(jsonPath("$.data.summary.offline").value(offline))
                .andExpect(jsonPath("$.data.summary.openAlarms").value(openAlarms))
                .andExpect(jsonPath("$.data.summary.todayDistanceKm").value(distance));

        String code = jdbc.sql("SELECT code FROM fleet WHERE id = ?")
                .param(fleetId).query(String.class).single();
        mvc.perform(get("/api/fleets")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .param("keyword", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].totalVehicles").value(total))
                .andExpect(jsonPath("$.data[0].online").value(online))
                .andExpect(jsonPath("$.data[0].moving").value(moving))
                .andExpect(jsonPath("$.data[0].idle").value(idle))
                .andExpect(jsonPath("$.data[0].offline").value(offline))
                .andExpect(jsonPath("$.data[0].openAlarms").value(openAlarms))
                .andExpect(jsonPath("$.data[0].todayDistanceKm").value(distance));
    }

    private void createVehicle(String deviceId, String plateNo) {
        vehicles.insert(new Vehicle(deviceId, plateNo, "blue", "test", 1,
                null, null, null));
    }

    private void insertStatus(String deviceId, boolean online, double speed, String lastSeenAt) {
        jdbc.sql("""
                        INSERT INTO device_status
                            (device_id, online, last_seen_at, speed_kph, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .param(deviceId)
                .param(online ? 1 : 0)
                .param(lastSeenAt)
                .param(speed)
                .param("2026-08-12T03:00:00Z")
                .update();
    }

    private void insertAlarm(String deviceId, String status, String type) {
        jdbc.sql("""
                        INSERT INTO alarm_event
                            (device_id, type, title, source, level, status,
                             occurred_at, last_occurred_at, created_at, updated_at)
                        VALUES (?, ?, ?, 'PROTOCOL', 'WARNING', ?, ?, ?, ?, ?)
                        """)
                .param(deviceId)
                .param(type)
                .param(type)
                .param(status)
                .param("2026-08-12T02:00:00Z")
                .param("2026-08-12T02:00:00Z")
                .param("2026-08-12T02:00:00Z")
                .param("2026-08-12T02:00:00Z")
                .update();
    }

    private void insertDistance(String deviceId, double distance) {
        jdbc.sql("""
                        INSERT INTO vehicle_daily_stat
                            (device_id, stat_date, distance_km, updated_at)
                        VALUES (?, ?, ?, ?)
                        """)
                .param(deviceId)
                .param(businessDates.today().toString())
                .param(distance)
                .param("2026-08-12T03:00:00Z")
                .update();
    }

    private int count(String table) {
        Integer value = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
        return value == null ? 0 : value;
    }
}
