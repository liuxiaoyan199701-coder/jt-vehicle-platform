package org.yzh.web.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jtplatform.signal.auth.DeviceAuthMode;
import io.github.jtplatform.signal.auth.DeviceAuthenticationService;
import io.github.jtplatform.signal.delivery.SignalMessageDispatcher;
import io.github.jtplatform.signal.diagnostics.ConnectionEventEmitter;
import io.github.jtplatform.signal.auth.DeviceInformation;
import io.github.jtplatform.signal.auth.DeviceInformationSource;
import io.github.jtplatform.signal.session.RegistrationTokenStore;
import io.github.yezhihao.netmc.session.Session;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.t808.T0001;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0102;
import org.yzh.protocol.t808.T8100;
import org.yzh.web.model.enums.SessionKey;
import org.yzh.web.service.FileService;

class DeviceAuthenticationEndpointTest {
    @Test
    void registrationAndAuthenticationUseTheSignalSideDecision() {
        DeviceInformationSource knownDevice = terminalId -> Optional.of(
                new DeviceInformation(terminalId, terminalId, "canonical-mobile", "canonical-plate"));
        JT808Endpoint endpoint = endpoint(knownDevice);
        Session session = mock(Session.class);
        T0100 registration = registration("terminal-1", "packet-mobile");

        T8100 registrationResponse = endpoint.T0100(registration, session);

        assertEquals(T8100.Success, registrationResponse.getResultCode());
        assertNotNull(registrationResponse.getToken());

        T0102 authentication = authentication("packet-mobile", registrationResponse.getToken());
        T0001 authenticationResponse = endpoint.T0102(authentication, session);

        assertEquals(T0001.Success, authenticationResponse.getResultCode());
        verify(session, times(2)).setAttribute(org.mockito.ArgumentMatchers.eq(SessionKey.Device), any());
        verify(session).register("terminal-1", authentication);
    }

    @Test
    void registrationDiagnosticsUseTheArchivedTerminalIdRatherThanHeaderMobileNumber() {
        DeviceInformationSource knownDevice = terminalId -> Optional.of(
                new DeviceInformation(terminalId, terminalId, "canonical-mobile", "canonical-plate"));
        ConnectionEventEmitter diagnostics = mock(ConnectionEventEmitter.class);
        JT808Endpoint endpoint = endpoint(knownDevice, diagnostics);
        Session session = mock(Session.class);

        endpoint.T0100(registration("terminal-1", "header-mobile"), session);

        verify(session).setAttribute(SessionKey.DiagnosticDeviceId, "terminal-1");
        verify(diagnostics).connected(
                org.mockito.ArgumentMatchers.eq("terminal-1"), org.mockito.ArgumentMatchers.any());
        verify(diagnostics).registerResult(
                org.mockito.ArgumentMatchers.eq("terminal-1"),
                org.mockito.ArgumentMatchers.eq(T8100.Success),
                org.mockito.ArgumentMatchers.eq("注册成功"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authenticationDiagnosticsUseResolvedTerminalId() {
        DeviceInformationSource knownDevice = terminalId -> Optional.of(
                new DeviceInformation(terminalId, terminalId, "canonical-mobile", "canonical-plate"));
        ConnectionEventEmitter diagnostics = mock(ConnectionEventEmitter.class);
        JT808Endpoint endpoint = endpoint(knownDevice, diagnostics);
        Session session = mock(Session.class);
        T8100 registration = endpoint.T0100(registration("terminal-1", "header-mobile"), session);

        endpoint.T0102(authentication("header-mobile", registration.getToken()), session);

        verify(diagnostics).authResult(
                org.mockito.ArgumentMatchers.eq("terminal-1"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("鉴权成功"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingDeviceIsRejectedByBothRegistrationAndAuthentication() {
        JT808Endpoint endpoint = endpoint(terminalId -> Optional.empty());
        Session session = mock(Session.class);

        T8100 registrationResponse = endpoint.T0100(registration("unknown", "unknown-mobile"), session);
        T0001 authenticationResponse = endpoint.T0102(authentication("unknown-mobile", "invalid"), session);

        assertEquals(T8100.NotFoundTerminal, registrationResponse.getResultCode());
        assertNull(registrationResponse.getToken());
        assertEquals(T0001.Failure, authenticationResponse.getResultCode());
        verify(session, times(2)).removeAttribute(SessionKey.Device);
        verify(session, never()).register(any(), any());
        verify(session, never()).setAttribute(
                org.mockito.ArgumentMatchers.eq(SessionKey.Device), any());
    }

    private static JT808Endpoint endpoint(DeviceInformationSource source) {
        return endpoint(source, null);
    }

    private static JT808Endpoint endpoint(
            DeviceInformationSource source, ConnectionEventEmitter diagnostics) {
        DeviceAuthenticationService authenticationService = new DeviceAuthenticationService(
                source, DeviceAuthMode.LOCAL_LIST, null);
        return new JT808Endpoint(
                mock(FileService.class),
                new RegistrationTokenStore(),
                authenticationService,
                mock(SignalMessageDispatcher.class),
                diagnostics);
    }

    private static T0100 registration(String terminalId, String mobileNo) {
        T0100 message = new T0100();
        message.setDeviceId(terminalId);
        message.setClientId(mobileNo);
        message.setPlateNo("packet-plate");
        message.setSerialNo(7);
        return message;
    }

    private static T0102 authentication(String mobileNo, String token) {
        T0102 message = new T0102();
        message.setClientId(mobileNo);
        message.setToken(token);
        message.setSerialNo(8);
        return message;
    }
}
