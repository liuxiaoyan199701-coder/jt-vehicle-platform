package io.github.jtconsole.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.security.AuthorizationResolver;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.SessionTokenService;
import io.github.jtconsole.support.TestPrincipals;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * 按租户定向的推送路径。
 *
 * <p>聚合类通知（车队在线率、告警激增）没有设备号，走原来那两条路都不对：按设备过滤压根没得比，
 * 而「不带设备只发平台管理员」会让租户管理员一条也收不到——那恰恰是对他们最有价值的一类。
 * 这里覆盖新增的这条分支，以及**旧分支没有被顺手放宽**。
 */
class TenantDirectedBroadcastTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:9527";
    private static final long TENANT = 1L;
    private static final long OTHER_TENANT = 2L;

    @Test
    void aTenantAdministratorReceivesTheirOwnTenantsAggregateNotice() throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        LiveBroadcaster broadcaster = broadcaster(TestPrincipals.tenantAdmin(11L, TENANT));
        try {
            connect(broadcaster, received);

            broadcaster.publishToTenant("notice", TENANT, Map.of("summary", "告警激增"));

            assertTrue(poll(received).contains("\"type\":\"notice\""));
        } finally {
            broadcaster.destroy();
        }
    }

    @Test
    void anotherTenantsSessionNeverSeesIt() throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        LiveBroadcaster broadcaster = broadcaster(TestPrincipals.tenantAdmin(11L, OTHER_TENANT));
        try {
            connect(broadcaster, received);

            broadcaster.publishToTenant("notice", TENANT, Map.of("summary", "告警激增"));

            assertNull(received.poll(300, TimeUnit.MILLISECONDS));
        } finally {
            broadcaster.destroy();
        }
    }

    /** 部门受限不影响租户级通知：范围收窄是车辆维度的，通知的归属是租户维度的。 */
    @Test
    void aDepartmentRestrictedOperatorStillBelongsToTheTenant() throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        LiveBroadcaster broadcaster =
                broadcaster(TestPrincipals.departmentOperator(11L, TENANT, Set.of(7L)));
        try {
            connect(broadcaster, received);

            broadcaster.publishToTenant("notice", TENANT, Map.of("summary", "车队在线率偏低"));

            assertTrue(poll(received).contains("车队在线率偏低"));
        } finally {
            broadcaster.destroy();
        }
    }

    @Test
    void aPlatformAdministratorSeesTenantDirectedNoticesToo() throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        LiveBroadcaster broadcaster = broadcaster(TestPrincipals.platform());
        try {
            connect(broadcaster, received);

            broadcaster.publishToTenant("notice", TENANT, Map.of("summary", "告警激增"));

            assertTrue(poll(received).contains("告警激增"));
        } finally {
            broadcaster.destroy();
        }
    }

    /**
     * 设备类更新仍走原来那条路：按车辆归属过滤，与租户分支无关。
     *
     * <p>这台车不属于该会话，因此收不到——**新增分支不能顺手把设备过滤也放宽**。
     */
    @Test
    void deviceScopedUpdatesKeepGoingThroughOwnershipFilteringUnchanged() throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        LiveBroadcaster broadcaster = broadcaster(
                TestPrincipals.tenantAdmin(11L, TENANT), false);
        try {
            connect(broadcaster, received);

            broadcaster.publish(new HashMap<>(Map.of("deviceId", "device-9", "sequence", 1)));

            assertNull(received.poll(300, TimeUnit.MILLISECONDS));
        } finally {
            broadcaster.destroy();
        }
    }

    /** 既不带设备也不带租户的广播，语义原样保留：只给平台管理员。 */
    @Test
    void aBroadcastNamingNeitherDeviceNorTenantStillGoesOnlyToPlatformAdministrators()
            throws Exception {
        LinkedBlockingQueue<String> tenantReceived = new LinkedBlockingQueue<>();
        LiveBroadcaster tenantSide = broadcaster(TestPrincipals.tenantAdmin(11L, TENANT));
        try {
            connect(tenantSide, tenantReceived);
            tenantSide.broadcastLocation(Map.of("sequence", 1));
            assertNull(tenantReceived.poll(300, TimeUnit.MILLISECONDS));
        } finally {
            tenantSide.destroy();
        }

        LinkedBlockingQueue<String> platformReceived = new LinkedBlockingQueue<>();
        LiveBroadcaster platformSide = broadcaster(TestPrincipals.platform());
        try {
            connect(platformSide, platformReceived);
            platformSide.broadcastLocation(Map.of("sequence", 1));
            assertTrue(poll(platformReceived).contains("\"sequence\":1"));
        } finally {
            platformSide.destroy();
        }
    }

    private static String poll(LinkedBlockingQueue<String> received) throws InterruptedException {
        String payload = received.poll(2, TimeUnit.SECONDS);
        assertTrue(payload != null, "没有收到任何推送");
        return payload;
    }

    private static LiveBroadcaster broadcaster(AuthorizedPrincipal principal) {
        return broadcaster(principal, true);
    }

    private static LiveBroadcaster broadcaster(AuthorizedPrincipal principal, boolean ownsDevices) {
        AuthorizationResolver resolver = mock(AuthorizationResolver.class);
        when(resolver.resolve(anyLong())).thenReturn(Optional.of(principal));
        DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
        when(ownership.visibleTo(any(), any())).thenReturn(ownsDevices);
        return new LiveBroadcaster(
                new ObjectMapper(), 8, 8, 1, Duration.ofSeconds(30), resolver, ownership);
    }

    private static void connect(LiveBroadcaster broadcaster, LinkedBlockingQueue<String> sink)
            throws Exception {
        SessionTokenService tokens = new SessionTokenService(new ConsoleProperties());
        LiveWebSocketHandshakeInterceptor interceptor =
                new LiveWebSocketHandshakeInterceptor(tokens, Set.of(ALLOWED_ORIGIN));
        Map<String, Object> attributes = new HashMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
        headers.set("Sec-WebSocket-Protocol",
                "jt-console.v1, bearer." + tokens.issue(11L, "tester", null).token());
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        assertTrue(interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> sink.add(((TextMessage) invocation.getArgument(0)).getPayload()))
                .when(session).sendMessage(any(TextMessage.class));

        broadcaster.afterConnectionEstablished(session);
        assertEquals(1, broadcaster.subscriberCount());
    }
}
