package io.github.jtconsole.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.security.AuthorizationResolver;
import io.github.jtconsole.security.SessionTokenService;
import io.github.jtconsole.security.SessionTokenService.AuthenticatedSession;
import io.github.jtconsole.security.SessionTokenService.AuthenticationState;
import io.github.jtconsole.support.TestPrincipals;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class LiveUpdatesTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:9527";

    @Test
    void acceptsValidTokenFromTrimmedAllowedOrigin() {
        SessionTokenService tokens = tokenService();
        String token = tokens.issue(1L, "admin", null).token();
        LiveWebSocketHandshakeInterceptor interceptor =
                new LiveWebSocketHandshakeInterceptor(tokens, Set.of("  " + ALLOWED_ORIGIN + "  "));
        ServerHttpRequest request = request(ALLOWED_ORIGIN,
                "jt-console.v1, bearer." + token);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        assertTrue(interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes));
        assertEquals(Set.of(LiveWebSocketHandshakeInterceptor.AUTHENTICATED_SESSION_ATTRIBUTE),
                attributes.keySet());
        AuthenticatedSession authentication = assertInstanceOf(
                AuthenticatedSession.class,
                attributes.get(LiveWebSocketHandshakeInterceptor.AUTHENTICATED_SESSION_ATTRIBUTE));
        assertEquals("admin", authentication.username());
        assertEquals(AuthenticationState.ACTIVE, authentication.state());
        assertFalse(attributes.toString().contains(token));
        verify(response, never()).setStatusCode(any());
    }

    @Test
    void rejectsInvalidTokenBeforeRegisteringSession() {
        LiveWebSocketHandshakeInterceptor interceptor =
                new LiveWebSocketHandshakeInterceptor(tokenService(), Set.of(ALLOWED_ORIGIN));
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        assertFalse(interceptor.beforeHandshake(
                request(ALLOWED_ORIGIN, "jt-console.v1, bearer.not-issued"),
                response,
                mock(WebSocketHandler.class),
                attributes));

        assertTrue(attributes.isEmpty());
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsDisallowedOriginEvenWithValidToken() {
        SessionTokenService tokens = tokenService();
        String token = tokens.issue(1L, "admin", null).token();
        LiveWebSocketHandshakeInterceptor interceptor =
                new LiveWebSocketHandshakeInterceptor(tokens, Set.of(ALLOWED_ORIGIN));
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        assertFalse(interceptor.beforeHandshake(
                request("https://untrusted.example", "jt-console.v1, bearer." + token),
                response,
                mock(WebSocketHandler.class),
                attributes));

        assertTrue(attributes.isEmpty());
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsWildcardOriginConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new LiveWebSocketHandshakeInterceptor(tokenService(), Set.of("*")));
    }

    @Test
    void unauthenticatedConnectionNeverEntersSubscriberSet() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(4);
        WebSocketSession session = session("anonymous", Map.of());
        try {
            broadcaster.afterConnectionEstablished(session);

            assertEquals(0, broadcaster.subscriberCount());
            verify(session).close(any(CloseStatus.class));
        } finally {
            broadcaster.destroy();
        }
    }

    @Test
    void connectionEstablishedAfterDestroyIsClosedAndNeverRegistered() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(4);
        broadcaster.destroy();
        WebSocketSession socket = authenticatedSession("after-destroy");

        broadcaster.afterConnectionEstablished(socket);

        assertEquals(0, broadcaster.subscriberCount());
        verifyClosedWithStatus(socket, 1001, "Live broadcaster stopping");
    }

    @Test
    void registrationPausedBeforeCommitCannotSurviveConcurrentDestroy() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(4);
        SessionTokenService tokens = tokenService();
        Map<String, Object> attributes = authenticatedAttributes(
                tokens, tokens.issue(1L, "admin", null).token());
        CountDownLatch attributesRequested = new CountDownLatch(1);
        CountDownLatch continueRegistration = new CountDownLatch(1);
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("concurrent-destroy-registration");
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenAnswer(invocation -> {
            attributesRequested.countDown();
            awaitUninterruptibly(continueRegistration);
            return attributes;
        });
        Thread registration = Thread.ofVirtual().start(() -> {
            try {
                broadcaster.afterConnectionEstablished(socket);
            } catch (Throwable failure) {
                registrationFailure.set(failure);
            }
        });

        try {
            assertTrue(attributesRequested.await(2, TimeUnit.SECONDS));
            broadcaster.destroy();
            continueRegistration.countDown();
            registration.join(2_000);

            assertFalse(registration.isAlive());
            assertNull(registrationFailure.get());
            assertEquals(0, broadcaster.subscriberCount());
            verifyClosedWithStatus(socket, 1001, "Live broadcaster stopping");
        } finally {
            continueRegistration.countDown();
            broadcaster.destroy();
        }
    }

    @Test
    void destroyClosesAllEstablishedSocketsBeforeReturning() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(4);
        WebSocketSession first = authenticatedSession("destroy-first");
        WebSocketSession second = authenticatedSession("destroy-second");
        broadcaster.afterConnectionEstablished(first);
        broadcaster.afterConnectionEstablished(second);
        assertEquals(2, broadcaster.subscriberCount());

        broadcaster.destroy();

        assertEquals(0, broadcaster.subscriberCount());
        verifyClosedWithStatus(first, 1001, "Live broadcaster stopping");
        verifyClosedWithStatus(second, 1001, "Live broadcaster stopping");
    }

    @Test
    void destroyClosesSocketAndLetsBlockedSenderExitBeforeReturning() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(4);
        WebSocketSession socket = authenticatedSession("destroy-blocked-sender");
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch sendFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendStarted.countDown();
            awaitUninterruptibly(releaseSend);
            sendFinished.countDown();
            return null;
        }).when(socket).sendMessage(any(TextMessage.class));
        doAnswer(invocation -> {
            releaseSend.countDown();
            return null;
        }).when(socket).close(any(CloseStatus.class));

        try {
            broadcaster.afterConnectionEstablished(socket);
            assertTrue(broadcaster.publish(update(1)));
            assertTrue(sendStarted.await(2, TimeUnit.SECONDS));

            assertTimeout(Duration.ofSeconds(2), broadcaster::destroy);

            assertTrue(sendFinished.await(1, TimeUnit.SECONDS));
            assertEquals(0, broadcaster.subscriberCount());
            verifyClosedWithStatus(socket, 1001, "Live broadcaster stopping");
        } finally {
            releaseSend.countDown();
            broadcaster.destroy();
        }
    }

    @Test
    void sessionRevokedAfterHandshakeButBeforeRegistrationNeverEntersSubscriberSet() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(4);
        SessionTokenService tokens = new SessionTokenService(
                new ConsoleProperties(), List.of(broadcaster));
        SessionTokenService.TokenPair credentials = tokens.issue(1L, "admin", null);
        Map<String, Object> attributes = authenticatedAttributes(tokens, credentials.token());

        assertTrue(tokens.revokeSessionForAccessToken(credentials.token()));
        WebSocketSession socket = session("revoked-before-registration", attributes);
        try {
            broadcaster.afterConnectionEstablished(socket);

            assertEquals(0, broadcaster.subscriberCount());
            verifyClosedWithReason(socket, "Authentication session revoked");
        } finally {
            broadcaster.destroy();
        }
    }

    @Test
    void accessRotatedAfterHandshakeButBeforeRegistrationRejectsOnlyOldCredential() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(4);
        SessionTokenService tokens = new SessionTokenService(
                new ConsoleProperties(), List.of(broadcaster));
        SessionTokenService.TokenPair original = tokens.issue(1L, "admin", null);
        Map<String, Object> originalAttributes = authenticatedAttributes(tokens, original.token());
        SessionTokenService.TokenPair rotated = tokens
                .rotateRefreshToken(original.refreshToken())
                .orElseThrow();

        WebSocketSession originalSocket = session("rotated-before-registration", originalAttributes);
        WebSocketSession rotatedSocket = session(
                "current-after-pre-registration-rotation",
                authenticatedAttributes(tokens, rotated.token()));
        try {
            broadcaster.afterConnectionEstablished(originalSocket);
            assertEquals(0, broadcaster.subscriberCount());
            verifyClosedWithReason(originalSocket, "Access credential revoked");

            broadcaster.afterConnectionEstablished(rotatedSocket);
            assertEquals(1, broadcaster.subscriberCount());
            assertTrue(tokens.validateAccessToken(rotated.token()).isPresent());
            verify(rotatedSocket, never()).close(any(CloseStatus.class));
        } finally {
            broadcaster.destroy();
        }
    }

    @Test
    void blockingSenderDoesNotBlockPublishAndOnlySlowSessionIsIsolated() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(2);
        CountDownLatch slowSendStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowSend = new CountDownLatch(1);
        LinkedBlockingQueue<String> healthyMessages = new LinkedBlockingQueue<>();
        WebSocketSession slow = authenticatedSession("slow-session-with-sensitive-shape");
        WebSocketSession healthy = authenticatedSession("healthy-session");

        doAnswer(invocation -> {
            slowSendStarted.countDown();
            awaitUninterruptibly(releaseSlowSend);
            return null;
        }).when(slow).sendMessage(any(TextMessage.class));
        doAnswer(invocation -> {
            healthyMessages.add(invocation.getArgument(0, TextMessage.class).getPayload());
            return null;
        }).when(healthy).sendMessage(any(TextMessage.class));

        try {
            broadcaster.afterConnectionEstablished(slow);
            broadcaster.afterConnectionEstablished(healthy);

            assertTrue(broadcaster.publish(update(1)));
            assertTrue(slowSendStarted.await(2, TimeUnit.SECONDS));
            assertMessage(healthyMessages, 1);

            assertTimeout(Duration.ofSeconds(1), () -> assertTrue(broadcaster.publish(update(2))));
            assertMessage(healthyMessages, 2);
            assertTrue(broadcaster.publish(update(3)));
            assertMessage(healthyMessages, 3);
            assertTrue(broadcaster.publish(update(4)));
            assertMessage(healthyMessages, 4);

            awaitCondition(() -> broadcaster.metrics().slowSessions() == 1);
            awaitCondition(() -> broadcaster.subscriberCount() == 1);

            assertTrue(broadcaster.publish(update(5)));
            assertMessage(healthyMessages, 5);
            LiveBroadcaster.BroadcastMetrics metrics = broadcaster.metrics();
            assertEquals(1, metrics.subscribers());
            assertEquals(2, metrics.dispatchQueueCapacity());
            assertEquals(0, metrics.dispatchOverflows());
            assertEquals(0, metrics.sendFailures());
            assertEquals(1, metrics.slowSessions());
            verify(slow, timeout(2000)).close(any(CloseStatus.class));
        } finally {
            releaseSlowSend.countDown();
            broadcaster.destroy();
        }
    }

    @Test
    void fullDispatchQueueDropsImmediatelyAndIncrementsOverflowMetric() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        CountDownLatch serializationStarted = new CountDownLatch(1);
        CountDownLatch releaseSerialization = new CountDownLatch(1);
        doAnswer(invocation -> {
            serializationStarted.countDown();
            awaitUninterruptibly(releaseSerialization);
            return "{\"type\":\"location\",\"data\":{}}";
        }).when(objectMapper).writeValueAsString(any(Object.class));
        LiveBroadcaster broadcaster = new LiveBroadcaster(
                objectMapper, 1, 4, 1, Duration.ofSeconds(30),
                platformAuthorizations(), platformOwnership());
        WebSocketSession session = authenticatedSession("overflow-test-session");

        try {
            broadcaster.afterConnectionEstablished(session);
            assertTrue(broadcaster.publish(update(1)));
            assertTrue(serializationStarted.await(2, TimeUnit.SECONDS));
            assertTrue(broadcaster.publish(update(2)));
            assertTimeout(Duration.ofSeconds(1), () -> assertFalse(broadcaster.publish(update(3))));

            LiveBroadcaster.BroadcastMetrics metrics = broadcaster.metrics();
            assertEquals(1, metrics.dispatchQueueDepth());
            assertEquals(1, metrics.dispatchQueueCapacity());
            assertEquals(1, metrics.dispatchOverflows());
        } finally {
            releaseSerialization.countDown();
            broadcaster.destroy();
        }
    }

    @Test
    void completedSendsDoNotAccumulateCancelledTimeoutTasks() throws Exception {
        LiveBroadcaster broadcaster = new LiveBroadcaster(
                new ObjectMapper(), 64, 64, 1, Duration.ofMinutes(5),
                platformAuthorizations(), platformOwnership());
        WebSocketSession session = authenticatedSession("timeout-cleanup-session");
        AtomicInteger sentMessages = new AtomicInteger();
        doAnswer(invocation -> {
            sentMessages.incrementAndGet();
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        try {
            broadcaster.afterConnectionEstablished(session);
            for (int sequence = 0; sequence < 32; sequence++) {
                assertTrue(broadcaster.publish(update(sequence)));
            }

            awaitCondition(() -> sentMessages.get() == 32);
            awaitCondition(() -> broadcaster.pendingControlTaskCount() == 0);
            assertEquals(0, broadcaster.pendingControlTaskCount());
        } finally {
            broadcaster.destroy();
        }
    }

    @Test
    void revokingAuthenticationSessionClosesOnlyItsEstablishedWebSocket() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(4);
        SessionTokenService tokens = new SessionTokenService(
                new ConsoleProperties(), List.of(broadcaster));
        SessionTokenService.TokenPair revokedCredentials = tokens.issue(1L, "admin", null);
        SessionTokenService.TokenPair activeCredentials = tokens.issue(1L, "admin", null);
        LiveWebSocketHandshakeInterceptor interceptor =
                new LiveWebSocketHandshakeInterceptor(tokens, Set.of(ALLOWED_ORIGIN));
        Map<String, Object> revokedAttributes = new HashMap<>();
        Map<String, Object> activeAttributes = new HashMap<>();

        assertTrue(interceptor.beforeHandshake(
                request(ALLOWED_ORIGIN, "jt-console.v1, bearer." + revokedCredentials.token()),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                revokedAttributes));
        assertTrue(interceptor.beforeHandshake(
                request(ALLOWED_ORIGIN, "jt-console.v1, bearer." + activeCredentials.token()),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                activeAttributes));

        WebSocketSession revokedSocket = session("revoked-websocket", revokedAttributes);
        WebSocketSession activeSocket = session("active-websocket", activeAttributes);
        try {
            broadcaster.afterConnectionEstablished(revokedSocket);
            broadcaster.afterConnectionEstablished(activeSocket);
            assertEquals(2, broadcaster.subscriberCount());

            assertTrue(tokens.revokeSessionForAccessToken(revokedCredentials.token()));
            awaitCondition(() -> broadcaster.subscriberCount() == 1);

            verifyClosedWithReason(revokedSocket, "Authentication session revoked");
            verify(activeSocket, never()).close(any(CloseStatus.class));
            assertTrue(tokens.validateAccessToken(activeCredentials.token()).isPresent());
        } finally {
            broadcaster.destroy();
        }
    }

    @Test
    void rotatingRefreshTokenClosesSocketUsingTheRevokedAccessCredential() throws Exception {
        LiveBroadcaster broadcaster = broadcaster(4);
        SessionTokenService tokens = new SessionTokenService(
                new ConsoleProperties(), List.of(broadcaster));
        SessionTokenService.TokenPair original = tokens.issue(1L, "admin", null);
        LiveWebSocketHandshakeInterceptor interceptor =
                new LiveWebSocketHandshakeInterceptor(tokens, Set.of(ALLOWED_ORIGIN));
        Map<String, Object> originalAttributes = new HashMap<>();

        assertTrue(interceptor.beforeHandshake(
                request(ALLOWED_ORIGIN, "jt-console.v1, bearer." + original.token()),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                originalAttributes));

        WebSocketSession originalSocket = session("rotated-access-websocket", originalAttributes);
        try {
            broadcaster.afterConnectionEstablished(originalSocket);
            assertEquals(1, broadcaster.subscriberCount());

            SessionTokenService.TokenPair rotated = tokens
                    .rotateRefreshToken(original.refreshToken())
                    .orElseThrow();
            awaitCondition(() -> broadcaster.subscriberCount() == 0);
            verifyClosedWithReason(originalSocket, "Access credential revoked");

            Map<String, Object> rotatedAttributes = new HashMap<>();
            assertTrue(interceptor.beforeHandshake(
                    request(ALLOWED_ORIGIN, "jt-console.v1, bearer." + rotated.token()),
                    mock(ServerHttpResponse.class),
                    mock(WebSocketHandler.class),
                    rotatedAttributes));
            WebSocketSession rotatedSocket = session("current-access-websocket", rotatedAttributes);
            broadcaster.afterConnectionEstablished(rotatedSocket);
            assertEquals(1, broadcaster.subscriberCount());
            verify(rotatedSocket, never()).close(any(CloseStatus.class));
        } finally {
            broadcaster.destroy();
        }
    }

    @Test
    void sessionDiagnosticLabelIsStableAndDoesNotExposeRawIdentifier() {
        String raw = "session-secret-value";
        String label = LiveBroadcaster.anonymizeSessionId(raw);

        assertEquals(label, LiveBroadcaster.anonymizeSessionId(raw));
        assertTrue(label.matches("[0-9a-f]{12}"));
        assertNotEquals(raw, label);
        assertFalse(label.contains("secret"));
    }

    private static LiveBroadcaster broadcaster(int sessionQueueCapacity) {
        return new LiveBroadcaster(
                new ObjectMapper(),
                2,
                sessionQueueCapacity,
                2,
                Duration.ofSeconds(30),
                platformAuthorizations(), platformOwnership());
    }

    private static SessionTokenService tokenService() {
        return new SessionTokenService(new ConsoleProperties());
    }

    /**
     * 本测试关注广播的排队、背压与撤销语义，因此把授权解析固定为平台管理员，
     * 让所有更新都能通过范围过滤；范围过滤本身另有专门的用例覆盖。
     */
    private static AuthorizationResolver platformAuthorizations() {
        AuthorizationResolver resolver = mock(AuthorizationResolver.class);
        when(resolver.resolve(anyLong()))
                .thenReturn(java.util.Optional.of(TestPrincipals.platform()));
        return resolver;
    }

    private static DeviceOwnershipCache platformOwnership() {
        DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
        when(ownership.visibleTo(any(), any())).thenReturn(true);
        return ownership;
    }

    private static ServerHttpRequest request(String origin, String protocols) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, origin);
        headers.set("Sec-WebSocket-Protocol", protocols);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    private static WebSocketSession authenticatedSession(String id) {
        SessionTokenService tokens = tokenService();
        return session(id, authenticatedAttributes(tokens, tokens.issue(1L, "admin", null).token()));
    }

    private static Map<String, Object> authenticatedAttributes(
            SessionTokenService tokens,
            String accessToken) {
        LiveWebSocketHandshakeInterceptor interceptor =
                new LiveWebSocketHandshakeInterceptor(tokens, Set.of(ALLOWED_ORIGIN));
        Map<String, Object> attributes = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(
                request(ALLOWED_ORIGIN, "jt-console.v1, bearer." + accessToken),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                attributes));
        return attributes;
    }

    private static WebSocketSession session(String id, Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static Map<String, Object> update(int sequence) {
        return Map.of("deviceId", "00123", "sequence", sequence);
    }

    private static void assertMessage(LinkedBlockingQueue<String> messages, int sequence)
            throws InterruptedException {
        String payload = messages.poll(2, TimeUnit.SECONDS);
        assertTrue(payload != null && payload.contains("\"sequence\":" + sequence),
                () -> "missing live update " + sequence + ", received=" + payload);
    }

    private static void verifyClosedWithReason(WebSocketSession session, String expectedReason)
            throws Exception {
        verifyClosedWithStatus(
                session, CloseStatus.POLICY_VIOLATION.getCode(), expectedReason);
    }

    private static void verifyClosedWithStatus(
            WebSocketSession session,
            int expectedCode,
            String expectedReason) throws Exception {
        verify(session, timeout(2000)).close(argThat(status ->
                status.getCode() == expectedCode
                        && expectedReason.equals(status.getReason())));
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
