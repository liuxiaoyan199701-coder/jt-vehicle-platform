package io.github.jtconsole.live;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.security.AuthorizationResolver;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.SessionRevocationListener;
import io.github.jtconsole.security.SessionTokenService.AuthenticatedSession;
import io.github.jtconsole.security.SessionTokenService.AuthenticationState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 向已鉴权浏览器异步广播实时位置，任何 WebSocket I/O 都不在投递或事务线程执行。
 */
@Component
public class LiveBroadcaster extends TextWebSocketHandler
        implements SubProtocolCapable, DisposableBean, SessionRevocationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LiveBroadcaster.class);
    private static final CloseStatus SLOW_CONSUMER = new CloseStatus(1013, "Slow live-update consumer");
    private static final CloseStatus SERVER_SHUTDOWN = new CloseStatus(1001, "Live broadcaster stopping");
    private static final CloseStatus ACCESS_CREDENTIAL_REVOKED =
            CloseStatus.POLICY_VIOLATION.withReason("Access credential revoked");
    private static final CloseStatus AUTHENTICATION_SESSION_REVOKED =
            CloseStatus.POLICY_VIOLATION.withReason("Authentication session revoked");
    private static final long SHUTDOWN_GRACE_MILLIS = 5_000;

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<PendingBroadcast> dispatchQueue;
    private final ObjectMapper objectMapper;
    private final AuthorizationResolver authorizations;
    private final DeviceOwnershipCache ownership;
    private final int dispatchQueueCapacity;
    private final int sessionQueueCapacity;
    private final long sendTimeoutMillis;
    private final ScheduledThreadPoolExecutor controlExecutor;
    private final List<Thread> dispatcherThreads;
    private final Object offerLock = new Object();
    private final Object dispatchOrderLock = new Object();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dispatchOverflows = new AtomicLong();
    private final AtomicLong sendFailures = new AtomicLong();
    private final AtomicLong slowSessions = new AtomicLong();
    private long nextSequenceToAssign;
    private long nextSequenceToDispatch;

    @Autowired
    public LiveBroadcaster(
            ObjectMapper objectMapper,
            ConsoleProperties properties,
            AuthorizationResolver authorizations,
            DeviceOwnershipCache ownership) {
        this(
                objectMapper,
                properties.getBroadcast().getDispatchQueueCapacity(),
                properties.getBroadcast().getSessionQueueCapacity(),
                properties.getBroadcast().getWorkerThreads(),
                properties.getBroadcast().getSendTimeout(),
                authorizations,
                ownership);
    }

    LiveBroadcaster(
            ObjectMapper objectMapper,
            int dispatchQueueCapacity,
            int sessionQueueCapacity,
            int workerThreads,
            Duration sendTimeout,
            AuthorizationResolver authorizations,
            DeviceOwnershipCache ownership) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.authorizations = Objects.requireNonNull(authorizations, "authorizations");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.dispatchQueueCapacity = requirePositive(dispatchQueueCapacity, "dispatchQueueCapacity");
        this.sessionQueueCapacity = requirePositive(sessionQueueCapacity, "sessionQueueCapacity");
        int workerCount = requirePositive(workerThreads, "workerThreads");
        Objects.requireNonNull(sendTimeout, "sendTimeout");
        if (sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("sendTimeout must be positive");
        }
        this.sendTimeoutMillis = sendTimeout.toMillis();
        this.dispatchQueue = new ArrayBlockingQueue<>(dispatchQueueCapacity);
        this.controlExecutor = new ScheduledThreadPoolExecutor(
                workerCount,
                Thread.ofPlatform().daemon(true).name("live-control-", 0).factory());
        this.controlExecutor.setRemoveOnCancelPolicy(true);
        this.controlExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        List<Thread> dispatchers = new ArrayList<>(workerCount);
        for (int index = 0; index < workerCount; index++) {
            dispatchers.add(Thread.ofPlatform()
                    .daemon(true)
                    .name("live-dispatcher-" + index)
                    .unstarted(this::dispatchLoop));
        }
        this.dispatcherThreads = List.copyOf(dispatchers);
        this.dispatcherThreads.forEach(Thread::start);
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(LiveWebSocketHandshakeInterceptor.APPLICATION_PROTOCOL);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Object authenticated = session.getAttributes().get(
                LiveWebSocketHandshakeInterceptor.AUTHENTICATED_SESSION_ATTRIBUTE);
        if (!(authenticated instanceof AuthenticatedSession authentication)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Authentication required"));
            return;
        }

        AuthenticationState stateBeforeRegistration = authentication.state();
        if (stateBeforeRegistration != AuthenticationState.ACTIVE) {
            session.close(closeStatusFor(stateBeforeRegistration));
            return;
        }

        ClientSession client = new ClientSession(session, authentication);
        ClientSession previous = null;
        boolean shuttingDown;
        synchronized (lifecycleLock) {
            shuttingDown = !running.get();
            if (!shuttingDown) {
                previous = sessions.putIfAbsent(session.getId(), client);
                if (previous == null) {
                    client.start();
                }
            }
        }
        if (shuttingDown) {
            session.close(SERVER_SHUTDOWN);
            return;
        }
        if (previous != null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Duplicate session"));
            return;
        }
        AuthenticationState stateAfterRegistration = authentication.state();
        if (stateAfterRegistration != AuthenticationState.ACTIVE) {
            removeSession(session.getId(), client, closeStatusFor(stateAfterRegistration), true);
            return;
        }
        LOGGER.debug("Live subscriber connected: {} (total {})", anonymizeSessionId(session.getId()), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session.getId(), null, false);
        LOGGER.debug("Live subscriber disconnected: {} (total {})", anonymizeSessionId(session.getId()), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sendFailures.incrementAndGet();
        removeSession(session.getId(), CloseStatus.SERVER_ERROR, true);
        LOGGER.debug("Removed failed live subscriber {} (failureType={})",
                anonymizeSessionId(session.getId()), exception.getClass().getName());
    }

    /**
     * 非阻塞发布提交后的位置更新。
     *
     * @return 无订阅者或成功入队时为 {@code true}，全局队列溢出时为 {@code false}
     */
    public boolean publish(Map<String, Object> update) {
        Objects.requireNonNull(update, "update");
        Map<String, Object> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(update));
        return offer("location", deviceIdOf(snapshot), null, snapshot);
    }

    /**
     * 兼容事务接收重构完成前的调用点。
     */
    public void broadcastLocation(Object data) {
        Objects.requireNonNull(data, "data");
        offer("location", data instanceof Map<?, ?> map ? deviceIdOf(map) : null, null, data);
    }

    /**
     * 向一个租户内的会话推送。
     *
     * <p><b>为什么要新增这条路径</b>：原有的两种形态对租户级结论都不合适——带设备号的按车辆
     * 归属过滤，而车队在线率、告警激增这类聚合结论压根没有设备号；不带设备号的
     * {@code canSee(null)} 只发平台管理员，租户管理员一条也收不到，而那恰恰是对他们
     * 最有价值的一类。
     *
     * <p>新增分支而**不放宽** {@code canSee(null)} 的既有语义：那条「只给平台管理员」的兜底
     * 是对的，它挡的是「没人显式指定收件人」的广播，不该因为多了一个用例就放开。
     *
     * @param tenantId 目标租户；{@code 0} 是平台级作用域，与简报、通知同一口径
     * @return 无订阅者或成功入队时为 {@code true}，全局队列溢出时为 {@code false}
     */
    public boolean publishToTenant(String type, long tenantId, Object data) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        return offer(type, null, tenantId, data);
    }

    private static String deviceIdOf(Map<?, ?> update) {
        Object deviceId = update.get("deviceId");
        return deviceId == null ? null : deviceId.toString();
    }

    private boolean offer(String type, String deviceId, Long tenantId, Object data) {
        if (!running.get() || sessions.isEmpty()) {
            return true;
        }

        long overflow;
        int depth;
        synchronized (offerLock) {
            if (!running.get() || sessions.isEmpty()) {
                return true;
            }
            PendingBroadcast pending =
                    new PendingBroadcast(nextSequenceToAssign, type, deviceId, tenantId, data);
            if (dispatchQueue.offer(pending)) {
                nextSequenceToAssign++;
                return true;
            }
            overflow = dispatchOverflows.incrementAndGet();
            depth = dispatchQueue.size();
        }
        LOGGER.warn("Live dispatch queue overflow (count={}, depth={}, capacity={})",
                overflow, depth, dispatchQueueCapacity);
        return false;
    }

    private void dispatchLoop() {
        while (running.get() || !dispatchQueue.isEmpty()) {
            try {
                PendingBroadcast pending = dispatchQueue.poll(250, TimeUnit.MILLISECONDS);
                if (pending == null) {
                    continue;
                }
                String json = null;
                try {
                    json = objectMapper.writeValueAsString(Map.of(
                            "type", pending.type(),
                            "data", pending.data()));
                } catch (RuntimeException serializationFailure) {
                    LOGGER.warn("Failed to serialize live update (failureType={})",
                            serializationFailure.getClass().getName());
                }
                dispatchInOrder(pending.sequence(), pending.deviceId(), pending.tenantId(), json);
            } catch (InterruptedException interrupted) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void dispatchInOrder(long sequence, String deviceId, Long tenantId, String json)
            throws InterruptedException {
        synchronized (dispatchOrderLock) {
            while (running.get() && sequence != nextSequenceToDispatch) {
                dispatchOrderLock.wait();
            }
            if (!running.get()) {
                return;
            }
            if (json != null) {
                // 每个会话按自己的数据范围过滤：未建档设备只推平台管理员，
                // 租户会话只收到本租户可见车辆的更新。
                sessions.values().stream()
                        .filter(client -> client.canSee(deviceId, tenantId))
                        .forEach(client -> client.offer(json));
            }
            nextSequenceToDispatch++;
            dispatchOrderLock.notifyAll();
        }
    }

    private void removeSession(String sessionId, CloseStatus status, boolean closeSocket) {
        ClientSession removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.stop(status, closeSocket);
        }
    }

    private void removeSession(
            String sessionId,
            ClientSession expected,
            CloseStatus status,
            boolean closeSocket) {
        if (sessions.remove(sessionId, expected)) {
            expected.stop(status, closeSocket);
        }
    }

    @Override
    public void onAccessCredentialRevoked(String accessCredentialId) {
        if (!running.get() || accessCredentialId == null) {
            return;
        }
        closeRevokedSessions(
                client -> accessCredentialId.equals(client.authentication.accessCredentialId()),
                ACCESS_CREDENTIAL_REVOKED);
    }

    @Override
    public void onSessionRevoked(String authenticationSessionId) {
        if (!running.get() || authenticationSessionId == null) {
            return;
        }
        closeRevokedSessions(
                client -> authenticationSessionId.equals(client.authentication.sessionId()),
                AUTHENTICATION_SESSION_REVOKED);
    }

    private void closeRevokedSessions(Predicate<ClientSession> revoked, CloseStatus closeStatus) {
        sessions.forEach((webSocketSessionId, client) -> {
            if (revoked.test(client)) {
                removeSession(webSocketSessionId, client, closeStatus, true);
            }
        });
    }

    public int subscriberCount() {
        return sessions.size();
    }

    public BroadcastMetrics metrics() {
        return new BroadcastMetrics(
                sessions.size(),
                dispatchQueue.size(),
                dispatchQueueCapacity,
                dispatchOverflows.get(),
                sendFailures.get(),
                slowSessions.get());
    }

    int pendingControlTaskCount() {
        return controlExecutor.getQueue().size();
    }

    @Override
    public void destroy() {
        List<ClientSession> closingSessions;
        synchronized (lifecycleLock) {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            closingSessions = List.copyOf(sessions.values());
            sessions.clear();
        }

        dispatcherThreads.forEach(Thread::interrupt);
        synchronized (dispatchOrderLock) {
            dispatchOrderLock.notifyAll();
        }
        closingSessions.forEach(client -> client.stop(SERVER_SHUTDOWN, true));
        controlExecutor.shutdown();

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_GRACE_MILLIS);
        boolean interrupted = false;
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0
                    && !controlExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                LOGGER.warn("Live broadcaster close tasks exceeded shutdown grace period");
                controlExecutor.shutdownNow();
            }
            for (ClientSession client : closingSessions) {
                client.awaitSenderTermination(deadline);
            }
            for (Thread dispatcher : dispatcherThreads) {
                joinUntil(dispatcher, deadline);
            }
        } catch (InterruptedException shutdownInterrupted) {
            interrupted = true;
            controlExecutor.shutdownNow();
        } finally {
            if (!controlExecutor.isTerminated()) {
                controlExecutor.shutdownNow();
            }
            dispatchQueue.clear();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final class ClientSession {
        private final WebSocketSession session;
        private final AuthenticatedSession authentication;
        private final ArrayBlockingQueue<String> outbound = new ArrayBlockingQueue<>(sessionQueueCapacity);
        private final AtomicBoolean active = new AtomicBoolean(true);
        private Thread senderThread;

        private ClientSession(
                WebSocketSession session,
                AuthenticatedSession authentication) {
            this.session = session;
            this.authentication = authentication;
        }

        /**
         * 该会话是否应收到这条广播。
         *
         * <p>三条路径，按顺序判定：
         * <ol>
         *   <li>带设备标识 → 按车辆归属过滤（实时位置走这条，语义不变）；</li>
         *   <li>带租户标识 → 会话属于该租户，或调用者是平台管理员（租户级通知走这条）；</li>
         *   <li>两者都不带 → 只发平台管理员，避免误扩散。</li>
         * </ol>
         *
         * <p>数据范围每次都经带 30 秒缓存的解析器取用，而不是在建连时定死：
         * 部门调整或角色收紧后，长连接不必重连就会跟上新的可见范围。
         */
        private boolean canSee(String deviceId, Long tenantId) {
            if (deviceId != null) {
                return authorizations.resolve(authentication.accountId())
                        .map(principal -> ownership.visibleTo(deviceId, principal.scope()))
                        .orElse(false);
            }
            if (tenantId != null) {
                return authorizations.resolve(authentication.accountId())
                        .map(principal -> principal.platform()
                                || tenantId.equals(principal.tenantId()))
                        .orElse(false);
            }
            // 既不指定设备也不指定租户的广播只发给平台管理员，避免误扩散。
            return authorizations.resolve(authentication.accountId())
                    .map(AuthorizedPrincipal::platform)
                    .orElse(false);
        }

        private synchronized void start() {
            if (!active.get() || senderThread != null) {
                return;
            }
            senderThread = Thread.ofVirtual()
                    .name("live-session-" + anonymizeSessionId(session.getId()))
                    .unstarted(this::sendLoop);
            senderThread.start();
        }

        private void offer(String json) {
            if (!active.get()) {
                return;
            }
            AuthenticationState authenticationState = authentication.state();
            if (authenticationState != AuthenticationState.ACTIVE) {
                removeSession(session.getId(), this, closeStatusFor(authenticationState), true);
                return;
            }
            if (!outbound.offer(json)) {
                slowSessions.incrementAndGet();
                LOGGER.warn("Closing slow live subscriber {} (queue capacity={})",
                        anonymizeSessionId(session.getId()), sessionQueueCapacity);
                removeSession(session.getId(), this, SLOW_CONSUMER, true);
            }
        }

        private void sendLoop() {
            while (active.get()) {
                try {
                    String json = outbound.take();
                    AuthenticationState authenticationState = authentication.state();
                    if (authenticationState != AuthenticationState.ACTIVE) {
                        removeSession(session.getId(), this, closeStatusFor(authenticationState), true);
                        return;
                    }
                    if (!session.isOpen()) {
                        removeSession(session.getId(), this, null, false);
                        return;
                    }
                    AtomicBoolean completed = new AtomicBoolean(false);
                    ScheduledFuture<?> timeout = controlExecutor.schedule(
                            () -> onSendTimeout(completed), sendTimeoutMillis, TimeUnit.MILLISECONDS);
                    try {
                        session.sendMessage(new TextMessage(json));
                    } finally {
                        completed.set(true);
                        timeout.cancel(false);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RejectedExecutionException executorStopped) {
                    return;
                } catch (IOException | IllegalStateException sendFailure) {
                    sendFailures.incrementAndGet();
                    LOGGER.debug("Dropping live subscriber {} (failureType={})",
                            anonymizeSessionId(session.getId()), sendFailure.getClass().getName());
                    removeSession(session.getId(), this, CloseStatus.SERVER_ERROR, true);
                    return;
                }
            }
        }

        private void onSendTimeout(AtomicBoolean completed) {
            if (completed.compareAndSet(false, true) && active.get()) {
                slowSessions.incrementAndGet();
                LOGGER.warn("Closing live subscriber {} after send timeout", anonymizeSessionId(session.getId()));
                removeSession(session.getId(), this, SLOW_CONSUMER, true);
            }
        }

        private synchronized void stop(CloseStatus status, boolean closeSocket) {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            outbound.clear();
            if (senderThread != null) {
                senderThread.interrupt();
            }
            if (closeSocket && status != null) {
                try {
                    controlExecutor.execute(() -> closeQuietly(status));
                } catch (RejectedExecutionException executorStopped) {
                    closeQuietly(status);
                }
            }
        }

        private void awaitSenderTermination(long deadline) throws InterruptedException {
            Thread sender;
            synchronized (this) {
                sender = senderThread;
            }
            if (sender != null && sender != Thread.currentThread()) {
                joinUntil(sender, deadline);
                if (sender.isAlive()) {
                    LOGGER.warn("Live sender did not stop within shutdown grace period: {}",
                            anonymizeSessionId(session.getId()));
                }
            }
        }

        private void closeQuietly(CloseStatus status) {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.close(status);
            } catch (IOException closeFailure) {
                LOGGER.debug("Failed to close live subscriber {} (failureType={})",
                        anonymizeSessionId(session.getId()), closeFailure.getClass().getName());
            }
        }
    }

    private static CloseStatus closeStatusFor(AuthenticationState state) {
        return switch (state) {
            case ACCESS_CREDENTIAL_REVOKED -> ACCESS_CREDENTIAL_REVOKED;
            case SESSION_REVOKED -> AUTHENTICATION_SESSION_REVOKED;
            case ACTIVE -> throw new IllegalArgumentException("Active authentication has no close status");
        };
    }

    private static void joinUntil(Thread thread, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !thread.isAlive()) {
            return;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
        int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
        thread.join(millis, nanos);
    }

    static String anonymizeSessionId(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record PendingBroadcast(
            long sequence, String type, String deviceId, Long tenantId, Object data) {}

    public record BroadcastMetrics(
            int subscribers,
            int dispatchQueueDepth,
            int dispatchQueueCapacity,
            long dispatchOverflows,
            long sendFailures,
            long slowSessions) {}
}
