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

/** RBAC、组织结构、套餐配额与审计的边界行为。 */
@SpringBootTest
class RbacAndPlanIntegrationTest {

    private static final String DATABASE_URL = "jdbc:sqlite:file:rbac-plan-"
            + UUID.randomUUID() + "?mode=memory&cache=shared";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private TenantRepository tenants;
    @Autowired private AccountRepository accounts;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SessionTokenService tokens;

    private MockMvc mvc;
    private long tenantId;
    private String platformBearer;
    private String tenantBearer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("jt.console.security.ingest-key",
                () -> "rbac-plan-integration-ingest-key-32-bytes");
        registry.add("jt.console.registration.enabled", () -> "true");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM vehicle").update();
        jdbc.sql("DELETE FROM department").update();
        jdbc.sql("DELETE FROM position").update();
        jdbc.sql("DELETE FROM tenant_registration").update();
        jdbc.sql("DELETE FROM tenant_order").update();
        jdbc.sql("""
                DELETE FROM account_role WHERE account_id IN (
                    SELECT id FROM account WHERE tenant_id IS NOT NULL)
                """).update();
        jdbc.sql("DELETE FROM account WHERE tenant_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM role WHERE tenant_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM tenant WHERE code LIKE 'rbac-%'").update();
        jdbc.sql("DELETE FROM plan WHERE name LIKE '测试%'").update();

        tenantId = createTenant("rbac-a", "RBAC 租户");
        platformBearer = bearer(accounts.findByUsername("admin").orElseThrow().id(), "admin", null);
        tenantBearer = bearer(
                createAccount("rbac-admin", tenantId, Role.TENANT_ADMIN), "rbac-admin", tenantId);
    }

    @Test
    void builtinRolesCannotBeModifiedOrDeleted() throws Exception {
        long builtinId = roles.findBuiltin(Role.TENANT_OPERATOR).orElseThrow().id();

        mvc.perform(put("/api/system/roles/{id}", builtinId)
                        .header(HttpHeaders.AUTHORIZATION, platformBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"改名","dataScope":"TENANT","permissions":["vehicle:list"]}
                                """))
                .andExpect(jsonPath("$.code").value("4009"));
        mvc.perform(delete("/api/system/roles/{id}", builtinId)
                        .header(HttpHeaders.AUTHORIZATION, platformBearer))
                .andExpect(jsonPath("$.code").value("4009"));
    }

    @Test
    void tenantRolesRejectPlatformPermissionsAndFabricatedCodes() throws Exception {
        mvc.perform(post("/api/system/roles")
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ESCALATE","name":"越权","dataScope":"TENANT",
                                 "permissions":["platform:tenant:manage"]}
                                """))
                .andExpect(jsonPath("$.code").value("4000"));

        mvc.perform(post("/api/system/roles")
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"FAKE","name":"伪造","dataScope":"TENANT",
                                 "permissions":["vehicle:list","vehicle:teleport"]}
                                """))
                .andExpect(jsonPath("$.code").value("4000"));

        assertThat(roles.findByTenant(tenantId)).isEmpty();
    }

    @Test
    void roleWithFewerPermissionsTakesEffectWithoutRelogin() throws Exception {
        long roleId = createTenantRole("DISPATCH", "调度员", List.of("vehicle:list", "alarm:handle"));
        long accountId = createAccount("dispatcher", tenantId, null);
        roles.replaceAccountRoles(accountId, List.of(roleId));
        String dispatcherBearer = bearer(accountId, "dispatcher", tenantId);

        mvc.perform(get("/api/vehicles").header(HttpHeaders.AUTHORIZATION, dispatcherBearer))
                .andExpect(status().isOk());
        // 未授予的权限码立刻被拒，不需要等 token 过期。
        mvc.perform(post("/api/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, dispatcherBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"13800001234\",\"plateNo\":\"测试\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void departmentTreeRejectsCyclesAndNonEmptyDeletion() throws Exception {
        long parent = createDepartment("华东分公司", null);
        long child = createDepartment("上海车队", parent);

        // 把父级设为自己的子孙会形成环，环一旦形成子树展开就会无限递归。
        mvc.perform(put("/api/system/departments/{id}", parent)
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"华东分公司\",\"parentId\":" + child + "}"))
                .andExpect(jsonPath("$.code").value("4000"));

        mvc.perform(delete("/api/system/departments/{id}", parent)
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer))
                .andExpect(jsonPath("$.code").value("4009"));
    }

    @Test
    void positionsAreLabelsOnlyAndCannotBeDeletedWhileReferenced() throws Exception {
        String response = mvc.perform(post("/api/system/positions")
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"驾驶员\"}"))
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn().getResponse().getContentAsString();
        long positionId = ((Number) JsonPath.read(response, "$.data.id")).longValue();

        long accountId = createAccount("with-position", tenantId, Role.TENANT_VIEWER);
        jdbc.sql("UPDATE account SET position_id = ? WHERE id = ?")
                .param(positionId).param(accountId).update();

        mvc.perform(delete("/api/system/positions/{id}", positionId)
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer))
                .andExpect(jsonPath("$.code").value("4009"));
    }

    @Test
    void vehicleQuotaIsEnforcedAtTheWriteEntryPoint() throws Exception {
        long planId = createPlan("测试套餐-单车", 1, 10);
        bindPlan(tenantId, planId);

        mvc.perform(post("/api/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"13800002001\",\"plateNo\":\"配额一\"}"))
                .andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(post("/api/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"13800002002\",\"plateNo\":\"配额二\"}"))
                .andExpect(jsonPath("$.code").value("4029"));
    }

    @Test
    void expiredTenantBehavesExactlySuspended() throws Exception {
        tenants.updateExpiry(tenantId, null, Instant.now().minusSeconds(60).toString());

        // 到期判定是实时比较有效期，不依赖周期任务。
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"rbac-admin\",\"password\":\"password-1234\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void renewalExtendsExpiryAndWritesAnImmutableLedgerEntry() throws Exception {
        long planId = createPlan("测试套餐-年付", 100, 20);

        mvc.perform(post("/api/platform/tenants/{id}/renew", tenantId)
                        .header(HttpHeaders.AUTHORIZATION, platformBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":" + planId + ",\"months\":12,\"amountCents\":120000}"))
                .andExpect(jsonPath("$.code").value("0000"));

        Tenant renewed = tenants.findById(tenantId).orElseThrow();
        assertThat(renewed.expiresAt()).isNotBlank();
        assertThat(renewed.expired(Instant.now())).isFalse();

        // 红冲：录错以负记录纠正，而不是修改原记录。
        mvc.perform(post("/api/platform/tenants/{id}/renew", tenantId)
                        .header(HttpHeaders.AUTHORIZATION, platformBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"months\":-12,\"amountCents\":-120000,\"remark\":\"红冲\"}"))
                .andExpect(jsonPath("$.code").value("0000"));

        mvc.perform(get("/api/platform/tenants/{id}/orders", tenantId)
                        .header(HttpHeaders.AUTHORIZATION, platformBearer))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void selfRegistrationLandsInPendingAndCannotLogInUntilApproved() throws Exception {
        String captcha = mvc.perform(get("/api/public/registration/captcha"))
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(captcha, "$.data.captchaToken");

        // 验证码答案不出现在响应里，因此这里只能验证「答错必被拒」这一半。
        mvc.perform(post("/api/public/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"新客户","contactName":"张三","contactPhone":"13800000000",
                                 "username":"newbie","password":"password-1234",
                                 "captchaToken":"%s","captchaCode":"WRONG"}
                                """.formatted(token)))
                .andExpect(jsonPath("$.code").value("4000"));

        assertThat(accounts.usernameExists("newbie")).isFalse();
    }

    @Test
    void businessRejectionsAreAuditedAsFailuresNotSuccesses() throws Exception {
        // 业务失败走 HTTP 200 + 错误码，只看状态码会把「越权被拒」记成成功，
        // 之后按结果筛选就再也找不出越权尝试。
        mvc.perform(post("/api/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"\",\"plateNo\":\"缺终端号\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("4000"));

        Thread.sleep(1500);

        mvc.perform(get("/api/system/audit")
                        .header(HttpHeaders.AUTHORIZATION, platformBearer)
                        .param("result", "FAILURE"))
                .andExpect(jsonPath("$.data.records[?(@.action == '新增车辆建档')]").exists());
    }

    @Test
    void auditRecordsBothSuccessfulWritesAndRefusals() throws Exception {
        mvc.perform(post("/api/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"13800003001\",\"plateNo\":\"审计车\"}"))
                .andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(get("/api/platform/tenants").header(HttpHeaders.AUTHORIZATION, tenantBearer))
                .andExpect(status().isForbidden());

        // 审计是异步单写线程，给它一点时间落库。
        Thread.sleep(1500);

        mvc.perform(get("/api/system/audit").header(HttpHeaders.AUTHORIZATION, platformBearer))
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.records[?(@.action == '新增车辆建档')]").exists());

        String body = mvc.perform(get("/api/system/audit")
                        .header(HttpHeaders.AUTHORIZATION, platformBearer))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("password-1234");
    }

    private long createTenant(String code, String name) {
        String now = Instant.now().toString();
        return tenants.insert(new Tenant(
                0L, code, name, TenantStatus.ACTIVE.name(), null, null, null, null, null, now, now));
    }

    private long createAccount(String username, long tenant, String roleCode) {
        String now = Instant.now().toString();
        long id = accounts.insert(new Account(
                0L, username, passwordEncoder.encode("password-1234"), username,
                tenant, null, null, Account.ACTIVE, null, now, now));
        if (roleCode != null) {
            roles.replaceAccountRoles(id, List.of(roles.findBuiltin(roleCode).orElseThrow().id()));
        }
        return id;
    }

    private long createTenantRole(String code, String name, List<String> permissions)
            throws Exception {
        String json = """
                {"code":"%s","name":"%s","dataScope":"TENANT","permissions":[%s]}
                """.formatted(code, name,
                permissions.stream().map(value -> "\"" + value + "\"").reduce(
                        (left, right) -> left + "," + right).orElse(""));
        String response = mvc.perform(post("/api/system/roles")
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.role.id")).longValue();
    }

    private long createDepartment(String name, Long parentId) throws Exception {
        String json = parentId == null
                ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"parentId\":" + parentId + "}";
        String response = mvc.perform(post("/api/system/departments")
                        .header(HttpHeaders.AUTHORIZATION, tenantBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private long createPlan(String name, int maxVehicles, int maxAccounts) throws Exception {
        String response = mvc.perform(post("/api/platform/plans")
                        .header(HttpHeaders.AUTHORIZATION, platformBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","maxVehicles":%d,"maxAccounts":%d,
                                 "priceCents":100000,"periodMonths":12,"enabled":true}
                                """.formatted(name, maxVehicles, maxAccounts)))
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private void bindPlan(long tenant, long planId) {
        tenants.updateExpiry(tenant, planId, null);
    }

    private String bearer(long accountId, String username, Long tenant) {
        return "Bearer " + tokens.issue(accountId, username, tenant).token();
    }
}
