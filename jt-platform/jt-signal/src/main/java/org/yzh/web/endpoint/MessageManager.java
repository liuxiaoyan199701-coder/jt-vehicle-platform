package org.yzh.web.endpoint;

import io.github.jtplatform.common.port.StreamCommandException;
import io.github.jtplatform.signal.diagnostics.ConnectionEventEmitter;
import io.github.jtplatform.signal.diagnostics.ConnectionEventEmitter.CommandOutcome;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionManager;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t808.T0001;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;
import reactor.core.publisher.Mono;

@Component
public class MessageManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageManager.class);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    private final SessionManager sessionManager;
    private final CommandResponseTracker rejectionTracker;
    private final ConnectionEventEmitter diagnostics;
    private final Duration responseTimeout;

    /** 测试便利构造：不发诊断事件。 */
    public MessageManager(SessionManager sessionManager, CommandResponseTracker rejectionTracker) {
        this(sessionManager, rejectionTracker, null);
    }

    @Autowired
    public MessageManager(
            SessionManager sessionManager, CommandResponseTracker rejectionTracker,
            ConnectionEventEmitter diagnostics) {
        this(sessionManager, rejectionTracker, diagnostics, RESPONSE_TIMEOUT);
    }

    /** 仅供测试缩短等待：生产路径一律用 {@link #RESPONSE_TIMEOUT}。 */
    MessageManager(
            SessionManager sessionManager, CommandResponseTracker rejectionTracker,
            ConnectionEventEmitter diagnostics, Duration responseTimeout) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.rejectionTracker = Objects.requireNonNull(rejectionTracker, "rejectionTracker");
        this.diagnostics = diagnostics;
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
    }

    public boolean isOnline(String deviceId) {
        return findSession(deviceId) != null;
    }

    public Mono<Void> notify(String deviceId, JTMessage request) {
        Session session = findSession(deviceId);
        if (session == null) {
            return Mono.error(offline(deviceId));
        }
        return session.notify(request);
    }

    public void send(String deviceId, JTMessage request) {
        Session session = findSession(deviceId);
        if (session == null) {
            throw offline(deviceId);
        }
        session.notify(request).subscribe(
                ignored -> { },
                error -> LOGGER.warn("Failed to send message to device {}", deviceId, error));
    }

    public <T> Mono<T> request(String deviceId, JTMessage request, Class<T> responseClass) {
        long commandId = request.reflectMessageId();
        Session session = findSession(deviceId);
        if (session == null) {
            emitCommandResult(deviceId, commandId, CommandOutcome.OFFLINE, null, null);
            return Mono.error(offline(deviceId));
        }
        String remoteAddress = session.getRemoteAddressStr();
        // 结局事件挂在 timeout 之前：源出错时走 doOnError，超时则源被取消、由回退分支发事件，
        // 两条路径互斥，一条指令只会产生一条结局事件。
        Mono<T> primary = session.request(request, responseClass)
                .doOnSuccess(response -> emitResponse(deviceId, commandId, response, remoteAddress))
                .doOnError(error -> emitCommandResult(
                        deviceId, commandId, CommandOutcome.FAILED, null, remoteAddress))
                .timeout(responseTimeout, Mono.defer(() -> {
                    emitCommandResult(deviceId, commandId, CommandOutcome.TIMEOUT, null, remoteAddress);
                    return Mono.error(new StreamCommandException(
                            "Device response timed out: " + deviceId));
                }))
                .onErrorMap(error -> error instanceof StreamCommandException
                        ? error
                        : new StreamCommandException("Failed to send command to device " + deviceId, error));

        // 期望的应答就是 T0001 本身时（如文本下发、云台控制），终端拒绝会直接完成
        // primary，无需快速失败通道。
        if (responseClass == T0001.class) {
            return primary;
        }

        // 期望专用应答（如 0x8500 的 T0201_0500、0x8801 的 T0805）时，终端回 T0001
        // 意味着拒绝。serialNo 由 netmc 的 requestInterceptor 在 session.request 内
        // 同步分配，此时已可用。
        int serialNo = request.getSerialNo();
        Mono<T> rejected = Mono.<Integer>create(sink -> rejectionTracker.register(session, serialNo, sink))
                .flatMap(resultCode -> {
                    emitCommandResult(
                            deviceId, commandId, CommandOutcome.REJECTED, resultCode, remoteAddress);
                    return Mono.<T>error(rejectionError(deviceId, resultCode));
                });
        return Mono.firstWithSignal(primary, rejected)
                .doFinally(ignored -> rejectionTracker.unregister(session, serialNo));
    }

    /** 应答本身是 T0001 时，结果码非 0 即为终端拒绝，不能一律记成成功。 */
    private void emitResponse(
            String deviceId, long commandId, Object response, String remoteAddress) {
        if (response == null) {
            return;
        }
        if (response instanceof T0001 reply) {
            emitCommandResult(deviceId, commandId,
                    reply.isSuccess() ? CommandOutcome.OK : CommandOutcome.REJECTED,
                    reply.getResultCode(), remoteAddress);
            return;
        }
        emitCommandResult(deviceId, commandId, CommandOutcome.OK, null, remoteAddress);
    }

    /** 诊断是旁路观测：任何失败都不得影响指令本身的下发与返回。 */
    private void emitCommandResult(
            String deviceId, long commandId, CommandOutcome outcome,
            Integer resultCode, String remoteAddress) {
        if (diagnostics == null || deviceId == null || deviceId.isBlank()) {
            return;
        }
        try {
            diagnostics.commandResult(deviceId, commandId, outcome, resultCode, remoteAddress);
        } catch (RuntimeException failure) {
            LOGGER.warn("指令结局事件发射失败：device={}, command={}", deviceId, commandId, failure);
        }
    }

    private static StreamCommandException rejectionError(String deviceId, int resultCode) {
        return switch (resultCode) {
            case 1 -> new StreamCommandException("Device failed to execute the command: " + deviceId);
            case 2 -> new StreamCommandException("Device rejected the command as invalid: " + deviceId);
            case 3 -> new StreamCommandException("Device rejected the command as unsupported: " + deviceId);
            case 4 -> new StreamCommandException("Device acknowledged the command as alarm confirmation: " + deviceId);
            default -> new StreamCommandException(
                    "Device rejected the command with result code " + resultCode + ": " + deviceId);
        };
    }

    public <T> Mono<T> request(JTMessage request, Class<T> responseClass) {
        return request(request.getClientId(), request, responseClass);
    }

    /**
     * Close the session of a device, if one is currently held by this instance.
     *
     * <p>Used when a device must stop being served immediately — for example when its owning
     * tenant is suspended. Dropping the connection only accelerates the outcome: the device is
     * free to reconnect, and it is the device authentication source that then refuses it.
     *
     * @return {@code true} when a live session was found and invalidated
     */
    public boolean disconnect(String identity) {
        Session session = findSession(identity);
        if (session == null) {
            return false;
        }
        session.invalidate();
        return true;
    }

    Session findSession(String identity) {
        if (identity == null || identity.isBlank()) {
            return null;
        }
        Session direct = sessionManager.get(identity);
        if (direct != null) {
            return direct;
        }
        for (Session session : sessionManager.values()) {
            if (identity.equals(session.getClientId())) {
                return session;
            }
            DeviceDO device = session.getAttribute(SessionKey.Device);
            if (device != null && identity.equals(device.getDeviceId())) {
                return session;
            }
        }
        return null;
    }

    private static StreamCommandException offline(String deviceId) {
        return new StreamCommandException("Device is offline: " + deviceId);
    }
}
