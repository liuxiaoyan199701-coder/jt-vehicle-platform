package io.github.jtconsole.terminal;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jtconsole.security.SessionTokenService;
import io.github.jtconsole.support.TestSchema;
import java.util.List;
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

/**
 * 端到端：网关的注册信封 → 终端台账 → 清单接口 → 一键建档。
 *
 * <p>逐段单测各自都对，串起来才看得出装配有没有接上——尤其是「注册信封根本没被
 * 任何投影接手」这种情况，单测里永远发现不了。
 */
@SpringBootTest
class TerminalRegistryIntegrationTest {

    private static final String DATABASE_URL = "jdbc:sqlite:file:terminal-registry-"
            + UUID.randomUUID() + "?mode=memory&cache=shared";
    private static final String INGEST_KEY = "terminal-registry-ingest-key-with-32-bytes";
    private static final String MOBILE = "138000000000";
    private static final String TERMINAL_ID = "1380000";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private SessionTokenService tokens;

    private MockMvc mvc;
    private String bearer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("jt.console.operations.zone-id", () -> "Asia/Shanghai");
        registry.add("jt.console.security.ingest-key", () -> INGEST_KEY);
    }

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        bearer = "Bearer " + tokens.issue(1L, "admin", null).token();
        for (String table : List.of("terminal", "device_status", "processed_event", "vehicle")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    void aRegistrationEnvelopeBecomesAnUnarchivedTerminalThatCanThenBeArchived() throws Exception {
        ingestRegistration();

        // 台账里出现这台终端，主键是手机号，终端 ID 只是附加列。
        mvc.perform(get("/api/terminals").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].deviceId").value(MOBILE))
                .andExpect(jsonPath("$.data.items[0].terminalId").value(TERMINAL_ID))
                .andExpect(jsonPath("$.data.items[0].archived").value(false))
                .andExpect(jsonPath("$.data.items[0].reportedPlate").value("TEST001"))
                .andExpect(jsonPath("$.data.items[0].deviceModel").value("SIMULATOR"))
                .andExpect(jsonPath("$.data.items[0].plateNo").doesNotExist());

        // 一键建档：车牌改成运维确认过的值，而不是照抄自报。
        mvc.perform(post("/api/terminals/{deviceId}/archive", MOBILE)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plateNo\":\"京A12345\",\"channelCount\":4,\"tenantId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.deviceId").value(MOBILE))
                .andExpect(jsonPath("$.data.plateNo").value("京A12345"));

        // 建档状态由连接得出，无需任何回写；自报车牌仍原样保留在台账里。
        mvc.perform(get("/api/terminals").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.items[0].archived").value(true))
                .andExpect(jsonPath("$.data.items[0].plateNo").value("京A12345"))
                .andExpect(jsonPath("$.data.items[0].reportedPlate").value("TEST001"));
    }

    @Test
    void filteringByUnarchivedFindsExactlyTheTerminalsStillWaitingForAProfile() throws Exception {
        ingestRegistration();

        mvc.perform(get("/api/terminals").param("archived", "false")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/terminals").param("archived", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /**
     * 用两个不同的 eventId：同一个会被业务库的幂等表直接挡在台账之前，
     * 那样验到的是幂等表而不是台账自身的 UPSERT。
     */
    @Test
    void aSecondRegistrationUpdatesTheSameLedgerRowInsteadOfAddingOne() throws Exception {
        ingestRegistration("evt-register-1");
        ingestRegistration("evt-register-2");

        mvc.perform(get("/api/terminals").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    /** payload 形态与网关 {@code ProtocolPayloadMapper} 为 0x0100 生成的一致（生产实测）。 */
    private void ingestRegistration() throws Exception {
        ingestRegistration("evt-register-1");
    }

    private void ingestRegistration(String eventId) throws Exception {
        mvc.perform(post("/ingest/jt-events")
                        .header("X-JT-Ingest-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s","deviceId":"%s","messageId":256,
                                 "serialNo":144,"protocolVersion":"JT/T 808-2019/1",
                                 "receivedAt":"2026-08-24T01:02:03Z","instanceId":"signal-1",
                                 "type":"register",
                                 "payload":{"cityId":100,"deviceId":"%s","deviceModel":"SIMULATOR",
                                            "makerId":"JT","plateColor":1,"plateNo":"TEST001",
                                            "provinceId":31}}
                                """.formatted(eventId, MOBILE, TERMINAL_ID)))
                // 投递成功是 204：网关不需要响应体，也就没有理由造一个。
                .andExpect(status().is2xxSuccessful());
    }
}
