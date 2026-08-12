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
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;
import reactor.core.publisher.Mono;

@Component
public class MessageManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageManager.class);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    private final SessionManager sessionManager;

    public MessageManager(SessionManager sessionManager) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
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
        return session.request(request, responseClass)
                .timeout(RESPONSE_TIMEOUT, Mono.error(new StreamCommandException(
                        "Device response timed out: " + deviceId)))
                .onErrorMap(error -> error instanceof StreamCommandException
                        ? error
                        : new StreamCommandException("Failed to send command to device " + deviceId, error));
    }

    public <T> Mono<T> request(JTMessage request, Class<T> responseClass) {
        return request(request.getClientId(), request, responseClass);
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
