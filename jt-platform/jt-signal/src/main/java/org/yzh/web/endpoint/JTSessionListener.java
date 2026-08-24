package org.yzh.web.endpoint;

import io.github.jtplatform.common.port.DeviceRouter;
import io.github.jtplatform.signal.diagnostics.ConnectionEventEmitter;
import io.github.jtplatform.signal.session.DeviceIdentity;
import io.github.yezhihao.netmc.core.model.Message;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionListener;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

public class JTSessionListener implements SessionListener {
    private static final BiConsumer<Session, Message> REQUEST_INTERCEPTOR = (session, message) -> {
        JTMessage request = (JTMessage) message;
        request.setClientId(session.getClientId());
        request.setSerialNo(session.nextSerialNo());
        if (request.getMessageId() == 0) {
            request.setMessageId(request.reflectMessageId());
        }

        DeviceDO device = session.getAttribute(SessionKey.Device);
        if (device != null && device.getProtocolVersion() > 0) {
            request.setVersion(true);
            request.setProtocolVersion(device.getProtocolVersion());
        }
    };

    private final DeviceRouter deviceRouter;
    private final String signalInstanceId;
    private final CommandResponseTracker rejectionTracker;
    private final ConnectionEventEmitter diagnostics;
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionDevices = new ConcurrentHashMap<>();

    public JTSessionListener(
            DeviceRouter deviceRouter, String signalInstanceId, CommandResponseTracker rejectionTracker) {
        this(deviceRouter, signalInstanceId, rejectionTracker, null);
    }

    public JTSessionListener(
            DeviceRouter deviceRouter, String signalInstanceId,
            CommandResponseTracker rejectionTracker, ConnectionEventEmitter diagnostics) {
        this.deviceRouter = Objects.requireNonNull(deviceRouter, "deviceRouter");
        if (signalInstanceId == null || signalInstanceId.isBlank()) {
            throw new IllegalArgumentException("signalInstanceId must not be blank");
        }
        this.signalInstanceId = signalInstanceId;
        this.rejectionTracker = Objects.requireNonNull(rejectionTracker, "rejectionTracker");
        this.diagnostics = diagnostics;
    }

    @Override
    public void sessionCreated(Session session) {
        session.requestInterceptor(REQUEST_INTERCEPTOR);
        rejectionTracker.attach(session);
        if (hasText(session.getId()) && hasText(session.getClientId())) {
            sessionDevices.put(session.getId(), session.getClientId());
        }
        if (diagnostics != null) {
            // 此刻通常还没有任何身份可用（协议头要到首条报文才解出），解析不出就不发——
            // 真正的连接建立事件由 T0100 那一处补，见 JT808Endpoint#T0100。
            DeviceIdentity.resolve(session).ifPresent(identity ->
                    diagnostics.connected(identity, session.getRemoteAddressStr()));
        }
    }

    @Override
    public void sessionRegistered(Session session) {
        DeviceDO device = session.getAttribute(SessionKey.Device);
        if (diagnostics != null && device != null && hasText(device.getDeviceId())
                && hasText(session.getId())) {
            // 顶替判定仍按终端 ID（netmc 的会话就是用它注册的），但事件按手机号归户。
            String previous = activeSessions.put(device.getDeviceId(), session.getId());
            if (previous != null && !previous.equals(session.getId())) {
                DeviceIdentity.resolve(session).ifPresent(identity ->
                        diagnostics.sessionReplaced(
                                identity, session.getRemoteAddressStr(), "被新会话顶替"));
            }
        }
        updateRoutes(session, true);
    }

    @Override
    public void sessionDestroyed(Session session) {
        rejectionTracker.clear(session);
        DeviceDO device = session.getAttribute(SessionKey.Device);
        String diagnosticDeviceId = session.getAttribute(SessionKey.DiagnosticDeviceId);
        String deviceId = device == null ? diagnosticDeviceId : device.getDeviceId();
        if (!hasText(deviceId)) {
            deviceId = session.getClientId();
        }
        if (!hasText(deviceId) && hasText(session.getId())) {
            deviceId = sessionDevices.get(session.getId());
        }
        String sessionId = session.getId();
        boolean current = hasText(deviceId)
                && (!hasText(sessionId) || activeSessions.get(deviceId) == null
                        || sessionId.equals(activeSessions.get(deviceId)));
        if (current && hasText(sessionId)) {
            activeSessions.remove(deviceId, sessionId);
            sessionDevices.remove(sessionId);
        }
        if (diagnostics != null && hasText(deviceId) && current) {
            // deviceId 这个局部变量是「会话顶替判定」用的终端 ID，事件本身必须按手机号归户。
            DeviceIdentity.resolve(session).ifPresent(identity ->
                    diagnostics.disconnected(
                            identity, session.getRemoteAddressStr(), null, "对端断开或空闲超时"));
        }
        updateRoutes(session, false);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void updateRoutes(Session session, boolean bind) {
        DeviceDO device = session.getAttribute(SessionKey.Device);
        if (device == null) {
            return;
        }
        updateRoute(device.getMobileNo(), bind);
        updateRoute(device.getDeviceId(), bind);
    }

    private void updateRoute(String identity, boolean bind) {
        if (identity == null || identity.isBlank()) {
            return;
        }
        if (bind) {
            deviceRouter.bind(identity, signalInstanceId);
        } else {
            deviceRouter.unbind(identity, signalInstanceId);
        }
    }
}
