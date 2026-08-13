package org.yzh.web.endpoint;

import io.github.jtplatform.common.port.DeviceRouter;
import io.github.yezhihao.netmc.core.model.Message;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionListener;
import java.util.Objects;
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

    public JTSessionListener(
            DeviceRouter deviceRouter, String signalInstanceId, CommandResponseTracker rejectionTracker) {
        this.deviceRouter = Objects.requireNonNull(deviceRouter, "deviceRouter");
        if (signalInstanceId == null || signalInstanceId.isBlank()) {
            throw new IllegalArgumentException("signalInstanceId must not be blank");
        }
        this.signalInstanceId = signalInstanceId;
        this.rejectionTracker = Objects.requireNonNull(rejectionTracker, "rejectionTracker");
    }

    @Override
    public void sessionCreated(Session session) {
        session.requestInterceptor(REQUEST_INTERCEPTOR);
        rejectionTracker.attach(session);
    }

    @Override
    public void sessionRegistered(Session session) {
        updateRoutes(session, true);
    }

    @Override
    public void sessionDestroyed(Session session) {
        rejectionTracker.clear(session);
        updateRoutes(session, false);
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
