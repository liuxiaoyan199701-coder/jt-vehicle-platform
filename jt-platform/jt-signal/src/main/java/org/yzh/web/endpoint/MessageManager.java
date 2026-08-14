package org.yzh.web.endpoint;

import io.github.jtplatform.common.port.StreamCommandException;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionManager;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public MessageManager(SessionManager sessionManager, CommandResponseTracker rejectionTracker) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.rejectionTracker = Objects.requireNonNull(rejectionTracker, "rejectionTracker");
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
        Session session = findSession(deviceId);
        if (session == null) {
            return Mono.error(offline(deviceId));
        }
        Mono<T> primary = session.request(request, responseClass)
                .timeout(RESPONSE_TIMEOUT, Mono.error(new StreamCommandException(
                        "Device response timed out: " + deviceId)))
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
                .flatMap(resultCode -> Mono.error(rejectionError(deviceId, resultCode)));
        return Mono.firstWithSignal(primary, rejected)
                .doFinally(ignored -> rejectionTracker.unregister(session, serialNo));
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
