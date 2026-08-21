package org.yzh.web.endpoint;

import io.github.jtplatform.signal.diagnostics.ConnectionEventEmitter;
import io.github.yezhihao.netmc.core.HandlerInterceptor;
import io.github.yezhihao.netmc.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t808.T0001;

public class JTHandlerInterceptor implements HandlerInterceptor<JTMessage> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JTHandlerInterceptor.class);
    private final ConnectionEventEmitter diagnostics;

    public JTHandlerInterceptor() {
        this(null);
    }

    public JTHandlerInterceptor(ConnectionEventEmitter diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public JTMessage notSupported(JTMessage request, Session session) {
        T0001 response = commonResponse(request, session, T0001.NotSupport);
        LOGGER.info("Unsupported terminal message id=0x{} from {}",
                Integer.toHexString(request.getMessageId()), session);
        return response;
    }

    @Override
    public JTMessage successful(JTMessage request, Session session) {
        return commonResponse(request, session, T0001.Success);
    }

    @Override
    public JTMessage exceptional(JTMessage request, Session session, Throwable error) {
        T0001 response = commonResponse(request, session, T0001.Failure);
        LOGGER.warn("Terminal message handler failed for id=0x{} from {}",
                Integer.toHexString(request.getMessageId()), session, error);
        if (diagnostics != null) {
            String diagnosticDeviceId = session.getAttribute(
                    org.yzh.web.model.enums.SessionKey.DiagnosticDeviceId);
            String deviceId = diagnosticDeviceId == null || diagnosticDeviceId.isBlank()
                    ? session.getClientId() : diagnosticDeviceId;
            if (deviceId != null && !deviceId.isBlank()) {
                diagnostics.protocolError(deviceId, error.getClass().getSimpleName(),
                        session.getRemoteAddressStr());
            }
        }
        return response;
    }

    @Override
    public boolean beforeHandle(JTMessage request, Session session) {
        int messageId = request.getMessageId();
        if (messageId != JT808.终端注册 && messageId != JT808.终端鉴权 && !session.isRegistered()) {
            LOGGER.warn("Message received before authentication: id=0x{}, session={}",
                    Integer.toHexString(messageId), session);
        }
        return true;
    }

    @Override
    public void afterHandle(JTMessage request, JTMessage response, Session session) {
        if (response == null) {
            return;
        }
        response.copyBy(request);
        response.setSerialNo(session.nextSerialNo());
        if (response.getMessageId() == 0) {
            response.setMessageId(response.reflectMessageId());
        }
    }

    private static T0001 commonResponse(JTMessage request, Session session, int resultCode) {
        T0001 response = new T0001();
        response.copyBy(request);
        response.setMessageId(JT808.平台通用应答);
        response.setSerialNo(session.nextSerialNo());
        response.setResponseSerialNo(request.getSerialNo());
        response.setResponseMessageId(request.getMessageId());
        response.setResultCode(resultCode);
        return response;
    }
}
