package io.github.jtconsole.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.jtconsole.domain.Account;
import io.github.jtconsole.domain.Role;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.domain.TenantStatus;
import io.github.jtconsole.repository.AccountRepository;
import io.github.jtconsole.repository.RoleRepository;
import io.github.jtconsole.repository.TenantRepository;
import io.github.jtconsole.security.SessionTokenService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 越权矩阵：两个租户各建一台车，逐个接口验证 B 看不到也改不了 A 的东西，
 * 且失败语义与「资源不存在」完全一致。
 */
@SpringBootTest
class TenancyIsolationIntegrationTest {

    private static final String DATABASE_URL = "jdbc:sqlite:file:tenancy-isolation-"
            + UUID.randomUUID() + "?mode=memory&cache=shared";
    private static final String DEVICE_A = "13800000001";
    private static final String DEVICE_B = "13800000002";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private TenantRepository tenants;
    @Autowired private AccountRepository accounts;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SessionTokenService tokens;

    private MockMvc mvc;
    private long tenantA;
    private long tenantB;
    private String platformBearer;
    private String bearerA;
    private String bearerB;
    private String viewerBearer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("jt.console.security.ingest-key",
                () -> "tenancy-isolation-ingest-key-at-least-32-bytes");
    }

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (String table : List.of(
                "geofence_vehicle", "geofence_presence", "alarm_condition_state", "alarm_event",
                "geofence", "fleet_vehicle", "fleet", "track_point", "device_status", "vehicle",
                "audit_log")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        // 只清租户账号的角色绑定：清空整张表会连引导出来的平台管理员一起解绑，
        // 之后每个平台接口都会 403，而那与被测行为无关。
        jdbc.sql("""
                DELETE FROM account_role WHERE account_id IN (
                    SELECT id FROM account WHERE tenant_id IS NOT NULL)
                """).update();
        jdbc.sql("DELETE FROM account WHERE tenant_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM tenant WHERE code LIKE 'iso-%'").update();

        tenantA = createTenant("iso-a", "隔离租户A");
        tenantB = createTenant("iso-b", "隔离租户B");

        long platformAdminId = accounts.findByUsername("admin").orElseThrow().id();
        platformBearer = bearer(platformAdminId, "admin", null);
        bearerA = bearer(createAccount("user-a", tenantA, Role.TENANT_ADMIN), "user-a", tenantA);
        bearerB = bearer(createAccount("user-b", tenantB, Role.TENANT_ADMIN), "user-b", tenantB);
        viewerBearer = bearer(
                createAccount("viewer-a", tenantA, Role.TENANT_VIEWER), "viewer-a", tenantA);

        createVehicle(bearerA, DEVICE_A, "京A00001");
        createVehicle(bearerB, DEVICE_B, "京B00002");
    }

    @Test
    void listsOnlyEverContainTheCallersOwnTenant() throws Exception {
        mvc.perform(get("/api/vehicles").header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_A));
        mvc.perform(get("/api/vehicles").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_B));
        // 平台管理员看得到全部，这是「跨租户」与「越权」的区别所在。
        mvc.perform(get("/api/vehicles").header(HttpHeaders.AUTHORIZATION, platformBearer))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void crossTenantReadsAreIndistinguishableFromNotFound() throws Exception {
        String foreign = readBody(get("/api/vehicles/{id}", DEVICE_A)
                .header(HttpHeaders.AUTHORIZATION, bearerB));
        String missing = readBody(get("/api/vehicles/{id}", "99999999999")
                .header(HttpHeaders.AUTHORIZATION, bearerB));
        assertThat(foreign).isEqualTo(missing);

        String foreignProfile = readBody(get("/api/vehicles/{id}/profile", DEVICE_A)
                .header(HttpHeaders.AUTHORIZATION, bearerB));
        String missingProfile = readBody(get("/api/vehicles/{id}/profile", "99999999999")
                .header(HttpHeaders.AUTHORIZATION, bearerB));
        assertThat(foreignProfile).isEqualTo(missingProfile);
    }

    @Test
    void crossTenantWritesChangeNothing() throws Exception {
        mvc.perform(put("/api/vehicles/{id}", DEVICE_A)
                        .header(HttpHeaders.AUTHORIZATION, bearerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plateNo\":\"被篡改\"}"))
                .andExpect(jsonPath("$.code").value("4004"));
        mvc.perform(delete("/api/vehicles/{id}", DEVICE_A)
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(jsonPath("$.code").value("4004"));

        mvc.perform(get("/api/vehicles/{id}", DEVICE_A)
                        .header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(jsonPath("$.data.plateNo").value("京A00001"));
    }

    @Test
    void duplicateDeviceIdAcrossTenantsConflictsWithoutRevealingTheOwner() throws Exception {
        String body = mvc.perform(post("/api/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, bearerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + DEVICE_A + "\",\"plateNo\":\"抢注\"}"))
                .andExpect(jsonPath("$.code").value("4009"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("iso-a", "隔离租户A");
    }

    @Test
    void commandAndStreamProxiesRejectForeignDevicesBeforeReachingTheGateway() throws Exception {
        // 网关在测试环境不可达：一旦请求真的发出去，就会变成 5030/5000 而不是 4004。
        mvc.perform(post("/api/commands/photo")
                        .header(HttpHeaders.AUTHORIZATION, bearerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + DEVICE_A + "\",\"channel\":1}"))
                .andExpect(jsonPath("$.code").value("4004"));
        mvc.perform(post("/api/stream/open")
                        .header(HttpHeaders.AUTHORIZATION, bearerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + DEVICE_A + "\",\"channel\":1}"))
                .andExpect(jsonPath("$.code").value("4004"));
    }

    @Test
    void viewerCanReadButEveryWriteIsRefused() throws Exception {
        mvc.perform(get("/api/vehicles").header(HttpHeaders.AUTHORIZATION, viewerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(post("/api/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, viewerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"13800009999\",\"plateNo\":\"只读新增\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/commands/photo")
                        .header(HttpHeaders.AUTHORIZATION, viewerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + DEVICE_A + "\",\"channel\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantUsersCannotSeeOrTouchPlatformScopedEndpoints() throws Exception {
        mvc.perform(get("/api/platform/tenants").header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/plans").header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/vehicles/{id}/tenant", DEVICE_A)
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":" + tenantB + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountManagementStaysInsideTheCallersTenant() throws Exception {
        mvc.perform(get("/api/system/accounts").header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(jsonPath("$.data[?(@.username == 'user-b')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.username == 'admin')]").isEmpty());

        long foreignAccountId = accounts.findByUsername("user-b").orElseThrow().id();
        mvc.perform(get("/api/system/accounts/{id}", foreignAccountId)
                        .header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(jsonPath("$.code").value("4004"));
    }

    @Test
    void platformAdminTransferMovesEveryHistoricalViewWithTheVehicle() throws Exception {
        mvc.perform(post("/api/vehicles/{id}/tenant", DEVICE_A)
                        .header(HttpHeaders.AUTHORIZATION, platformBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":" + tenantB + "}"))
                .andExpect(jsonPath("$.code").value("0000"));

        mvc.perform(get("/api/vehicles/{id}", DEVICE_A).header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(jsonPath("$.code").value("4004"));
        mvc.perform(get("/api/vehicles/{id}", DEVICE_A).header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(jsonPath("$.code").value("0000"));
    }

    @Test
    void suspendedTenantLosesItsSessionsImmediately() throws Exception {
        mvc.perform(get("/api/vehicles").header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isOk());

        mvc.perform(put("/api/platform/tenants/{id}/status", tenantA)
                        .header(HttpHeaders.AUTHORIZATION, platformBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.code").value("0000"));

        // 访问 token 有效期是 7 天，不即时撤销就等于停用形同虚设。
        mvc.perform(get("/api/vehicles").header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isUnauthorized());
        // 停用期间该租户的数据仍然入库，只是租户自己看不到；平台管理员照常可见。
        mvc.perform(get("/api/vehicles").header(HttpHeaders.AUTHORIZATION, platformBearer))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    private String readBody(org.springframework.test.web.servlet.RequestBuilder request)
            throws Exception {
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private long createTenant(String code, String name) {
        String now = Instant.now().toString();
        return tenants.insert(new Tenant(
                0L, code, name, TenantStatus.ACTIVE.name(), null, null, null, null, null, now, now));
    }

    private long createAccount(String username, long tenantId, String roleCode) {
        String now = Instant.now().toString();
        long id = accounts.insert(new Account(
                0L, username, passwordEncoder.encode("password-1234"), username,
                tenantId, null, null, Account.ACTIVE, null, now, now));
        roles.replaceAccountRoles(id, List.of(roles.findBuiltin(roleCode).orElseThrow().id()));
        return id;
    }

    private String bearer(long accountId, String username, Long tenantId) {
        return "Bearer " + tokens.issue(accountId, username, tenantId).token();
    }

    private void createVehicle(String bearer, String deviceId, String plateNo) throws Exception {
        String response = mvc.perform(post("/api/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"plateNo\":\"" + plateNo + "\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(response, "$.code")).isEqualTo("0000");
    }
}
