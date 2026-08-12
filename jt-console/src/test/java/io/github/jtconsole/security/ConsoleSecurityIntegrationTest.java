package io.github.jtconsole.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.jtconsole.security.SessionTokenService.AuthenticatedSession;
import io.github.jtconsole.security.SessionTokenService.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(ConsoleSecurityIntegrationTest.LogoutRaceConfiguration.class)
class ConsoleSecurityIntegrationTest {

    private static final String PASSWORD = "integration-password";
    private static final String INGEST_KEY = "integration-ingest-key-with-at-least-32-bytes";
    private static final String DATABASE_URL =
            "jdbc:sqlite:file:console-security-" + UUID.randomUUID()
                    + "?mode=memory&cache=shared";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionTokenService tokens;

    @Autowired
    private LogoutRaceInterceptor logoutRace;

    private MockMvc mvc;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("jt.console.security.deployment-mode", () -> "true");
        registry.add("jt.console.security.admin-username", () -> "admin");
        registry.add("jt.console.security.admin-password-hash", () ->
                new BCryptPasswordEncoder().encode(PASSWORD));
        registry.add("jt.console.security.ingest-key", () -> INGEST_KEY);
        registry.add("jt.console.security.allowed-origins", () ->
                "https://console.example.test");
        registry.add("jt.console.security.rate-limit.max-failures", () -> "3");
        registry.add("jt.console.security.rate-limit.window", () -> "1m");
        registry.add("jt.console.security.rate-limit.block-duration", () -> "5m");
    }

    @BeforeEach
    void createMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void correctAndWrongLoginUseRealHttpStatusAndDoNotExposeSecrets() throws Exception {
        mvc.perform(from(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"admin\",\"password\":\"wrong\"}"),
                        "198.51.100.10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("4001"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("wrong"))));

        MvcResult login = login("198.51.100.11");
        String body = login.getResponse().getContentAsString();
        assertThat(read(body, "$.data.token")).isNotBlank();
        assertThat(read(body, "$.data.refreshToken")).isNotBlank();
        assertThat(read(body, "$.data.accessTokenExpiresAt")).isNotBlank();
        assertThat(read(body, "$.data.refreshTokenExpiresAt")).isNotBlank();
        assertThat(body).doesNotContain(PASSWORD, "passwordHash");
    }

    @Test
    void protectedApisRejectMissingAndForgedTokensButAcceptIssuedToken() throws Exception {
        mvc.perform(get("/api/monitor/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("8888"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/diagnostics/events"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/monitor/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer forged-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("forged-token"))));

        String accessToken = accessToken(login("198.51.100.20"));
        mvc.perform(get("/api/monitor/stats")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(get("/api/diagnostics/events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liveBroadcast.subscribers").isNumber())
                .andExpect(jsonPath("$.data.liveBroadcast.queueDepth").isNumber())
                .andExpect(jsonPath("$.data.liveBroadcast.queueCapacity").isNumber())
                .andExpect(jsonPath("$.data.liveBroadcast.overflows").isNumber())
                .andExpect(jsonPath("$.data.liveBroadcast.sendFailures").isNumber())
                .andExpect(jsonPath("$.data.liveBroadcast.slowSessions").isNumber());
    }

    @Test
    void refreshRotatesBothCredentialsAndRejectsReplay() throws Exception {
        MvcResult login = login("198.51.100.30");
        String oldAccess = accessToken(login);
        String oldRefresh = refreshToken(login);

        MvcResult refreshed = refresh(oldRefresh, "198.51.100.31", 200);
        String newAccess = accessToken(refreshed);
        String newRefresh = refreshToken(refreshed);
        assertThat(newAccess).isNotEqualTo(oldAccess);
        assertThat(newRefresh).isNotEqualTo(oldRefresh);

        refresh(oldRefresh, "198.51.100.32", 401);
        mvc.perform(get("/api/monitor/stats")
                        .header(HttpHeaders.AUTHORIZATION, bearer(oldAccess)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/monitor/stats")
                        .header(HttpHeaders.AUTHORIZATION, bearer(newAccess)))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesTheWholeSession() throws Exception {
        MvcResult login = login("198.51.100.40");
        String accessToken = accessToken(login);
        String refreshToken = refreshToken(login);

        mvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"));

        mvc.perform(get("/api/monitor/stats")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isUnauthorized());
        refresh(refreshToken, "198.51.100.41", 401);
    }

    @Test
    void logoutRevokesRotatedCredentialsWhenRefreshWinsAfterBearerAuthentication() throws Exception {
        MvcResult login = login("198.51.100.42");
        String oldAccess = accessToken(login);
        String oldRefresh = refreshToken(login);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        logoutRace.arm();

        Future<MvcResult> logout = executor.submit(() -> mvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(oldAccess)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn());
        try {
            assertThat(logoutRace.awaitAuthenticated()).isTrue();
            Authentication authentication = logoutRace.authentication();
            assertThat(authentication.getPrincipal()).isInstanceOf(AuthenticatedSession.class);
            assertThat(authentication.getCredentials()).isNull();
            assertThat(authentication.toString()).doesNotContain(oldAccess, oldRefresh);

            TokenPair rotated = tokens.rotateRefreshToken(oldRefresh).orElseThrow();
            logoutRace.proceed();
            logout.get(5, TimeUnit.SECONDS);

            assertThat(tokens.validateAccessToken(rotated.token())).isEmpty();
            assertThat(tokens.rotateRefreshToken(rotated.refreshToken())).isEmpty();
            assertThat(((AuthenticatedSession) authentication.getPrincipal()).state())
                    .isEqualTo(SessionTokenService.AuthenticationState.SESSION_REVOKED);
        } finally {
            logoutRace.proceed();
            executor.shutdownNow();
            logoutRace.reset();
        }
    }

    @Test
    void loginAndRefreshFailuresAreRateLimitedBySource() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mvc.perform(from(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userName\":\"unknown-rate-user\",\"password\":\"wrong\"}"),
                            "198.51.100.50"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(from(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"unknown-rate-user\",\"password\":\"wrong\"}"),
                        "198.51.100.50"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("4290"));

        for (int attempt = 0; attempt < 3; attempt++) {
            refresh("invalid-refresh-" + attempt, "198.51.100.51", 401);
        }
        refresh("another-invalid-refresh", "198.51.100.51", 429);
    }

    @Test
    void defaultDenyUsesJsonForbiddenAndHealthRemainsPublic() throws Exception {
        String accessToken = accessToken(login("198.51.100.60"));
        mvc.perform(get("/not-public")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("8888"));

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void ingestKeyIsCheckedBeforeDeserializationAndAnyDatabaseWrite() throws Exception {
        int processedBefore = count("processed_event");
        int tracksBefore = count("track_point");
        int statusesBefore = count("device_status");

        mvc.perform(post("/ingest/jt-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{definitely-not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("8888"));

        mvc.perform(post("/ingest/jt-events")
                        .header("X-JT-Ingest-Key", "wrong-ingest-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEnvelope("rejected-" + UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        assertThat(count("processed_event")).isEqualTo(processedBefore);
        assertThat(count("track_point")).isEqualTo(tracksBefore);
        assertThat(count("device_status")).isEqualTo(statusesBefore);

        String acceptedEvent = "accepted-" + UUID.randomUUID();
        mvc.perform(post("/ingest/jt-events")
                        .header("X-JT-Ingest-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEnvelope(acceptedEvent)))
                .andExpect(status().isNoContent());
        Integer accepted = jdbc.sql(
                        "SELECT COUNT(*) FROM processed_event WHERE event_id = ?")
                .param(acceptedEvent)
                .query(Integer.class)
                .single();
        assertThat(accepted).isEqualTo(1);
    }

    private MvcResult login(String sourceAddress) throws Exception {
        return mvc.perform(from(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"admin\",\"password\":\""
                                + PASSWORD + "\"}"), sourceAddress))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andReturn();
    }

    private MvcResult refresh(String refreshToken, String sourceAddress, int expectedStatus)
            throws Exception {
        return mvc.perform(from(post("/api/auth/refreshToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"),
                        sourceAddress))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Integer.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LogoutRaceConfiguration {

        @Bean
        LogoutRaceInterceptor logoutRaceInterceptor() {
            return new LogoutRaceInterceptor();
        }

        @Bean
        WebMvcConfigurer logoutRaceWebMvcConfigurer(LogoutRaceInterceptor interceptor) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor);
                }
            };
        }
    }

    static final class LogoutRaceInterceptor implements HandlerInterceptor {
        private final AtomicReference<Authentication> authentication = new AtomicReference<>();
        private volatile CountDownLatch authenticated;
        private volatile CountDownLatch proceed;

        void arm() {
            authentication.set(null);
            authenticated = new CountDownLatch(1);
            proceed = new CountDownLatch(1);
        }

        boolean awaitAuthenticated() throws InterruptedException {
            CountDownLatch latch = authenticated;
            return latch != null && latch.await(5, TimeUnit.SECONDS);
        }

        Authentication authentication() {
            return authentication.get();
        }

        void proceed() {
            CountDownLatch latch = proceed;
            if (latch != null) {
                latch.countDown();
            }
        }

        void reset() {
            authenticated = null;
            proceed = null;
            authentication.set(null);
        }

        @Override
        public boolean preHandle(
                HttpServletRequest request,
                HttpServletResponse response,
                Object handler) throws InterruptedException {
            CountDownLatch entered = authenticated;
            CountDownLatch continuation = proceed;
            if ("/api/auth/logout".equals(request.getRequestURI())
                    && entered != null
                    && continuation != null) {
                authentication.set(SecurityContextHolder.getContext().getAuthentication());
                entered.countDown();
                continuation.await();
            }
            return true;
        }
    }

    private static MockHttpServletRequestBuilder from(
            MockHttpServletRequestBuilder request,
            String sourceAddress) {
        return request.with(mock -> {
            mock.setRemoteAddr(sourceAddress);
            return mock;
        });
    }

    private static String validEnvelope(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "deviceId": "013800000001",
                  "messageId": 2,
                  "serialNo": 1,
                  "protocolVersion": "2019",
                  "receivedAt": "2026-08-11T12:00:00Z",
                  "instanceId": "signal-1",
                  "type": "heartbeat",
                  "payload": {}
                }
                """.formatted(eventId);
    }

    private static String accessToken(MvcResult result) throws Exception {
        return read(result.getResponse().getContentAsString(), "$.data.token");
    }

    private static String refreshToken(MvcResult result) throws Exception {
        return read(result.getResponse().getContentAsString(), "$.data.refreshToken");
    }

    private static String read(String json, String path) {
        return JsonPath.read(json, path);
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
