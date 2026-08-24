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
import io.github.jtplatform.signal.session.DeviceIdentity;
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

    /**
     * 连接事件按<b>手机号</b>归户，终端 ID 只作附注。
     *
     * <p>平台没有任何一张表按终端 ID 建键：车辆档案、轨迹、报文日志一律按手机号，
     * 连网关拿 terminalId 去问控制台的 {@code DeviceRegistryController} 也是转手去 vehicle 表查的。
     * 用终端 ID 发事件的后果全是静默的——控制台归不到租户（tenant_id 记 NULL）、
     * 按车辆设备号查连接记录永远为空、体检的连接维度因此判不出结论，而接口全程 200。
     * 2026-08-24 生产库上实测到同一张 connection_event 里 COMMAND_RESULT 用手机号、
     * CONNECTED 用终端 ID，本测试此前锁的正是错误的那一半。
     */
    @Test
    void registrationDiagnosticsAreKeyedByMobileNumberWithTheTerminalIdKeptAsAnAnnotation() {
        DeviceInformationSource knownDevice = terminalId -> Optional.of(
                new DeviceInformation(terminalId, terminalId, "canonical-mobile", "canonical-plate"));
        ConnectionEventEmitter diagnostics = mock(ConnectionEventEmitter.class);
        JT808Endpoint endpoint = endpoint(knownDevice, diagnostics);
        Session session = statefulSession();

        endpoint.T0100(registration("terminal-1", "header-mobile"), session);

        verify(session).setAttribute(SessionKey.DiagnosticDeviceId, "terminal-1");
        // 注册被拒的设备后续还会断开、还会报协议错误，那时只剩这个副本能给出手机号。
        verify(session).setAttribute(SessionKey.DiagnosticMobileNo, "header-mobile");
        // 鉴权已通过，档案手机号（canonical-mobile）比终端自报的报文头更权威；
        // 连接建立与注册结局共用这一个身份，同一次注册的两条事件因此不会落到两个键上。
        DeviceIdentity expected = new DeviceIdentity("canonical-mobile", "terminal-1");
        verify(diagnostics).connected(
                org.mockito.ArgumentMatchers.eq(expected), org.mockito.ArgumentMatchers.any());
        verify(diagnostics).registerResult(
                org.mockito.ArgumentMatchers.eq(expected),
                org.mockito.ArgumentMatchers.eq(T8100.Success),
                org.mockito.ArgumentMatchers.eq("注册成功"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authenticationDiagnosticsAreKeyedByMobileNumberToo() {
        DeviceInformationSource knownDevice = terminalId -> Optional.of(
                new DeviceInformation(terminalId, terminalId, "canonical-mobile", "canonical-plate"));
        ConnectionEventEmitter diagnostics = mock(ConnectionEventEmitter.class);
        JT808Endpoint endpoint = endpoint(knownDevice, diagnostics);
        Session session = statefulSession();
        T8100 registration = endpoint.T0100(registration("terminal-1", "header-mobile"), session);

        endpoint.T0102(authentication("header-mobile", registration.getToken()), session);

        // 鉴权成功后会话里有档案，手机号取自档案（canonical-mobile），终端 ID 仍是自报的那个。
        verify(diagnostics).authResult(
                org.mockito.ArgumentMatchers.eq(new DeviceIdentity("canonical-mobile", "terminal-1")),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("鉴权成功"),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * 会话属性要真的存得住：诊断身份跨 T0100 与 T0102 两次调用累积，
     * 无状态的 mock 会让「注册时留下的手机号副本」这条路径永远测不到。
     */
    private static Session statefulSession() {
        java.util.Map<SessionKey, Object> attributes = new java.util.HashMap<>();
        Session session = mock(Session.class);
        org.mockito.Mockito.lenient()
                .when(session.getAttribute(org.mockito.ArgumentMatchers.any(SessionKey.class)))
                .thenAnswer(invocation -> attributes.get(invocation.<SessionKey>getArgument(0)));
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(session).setAttribute(
                org.mockito.ArgumentMatchers.any(SessionKey.class), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            attributes.remove(invocation.<SessionKey>getArgument(0));
            return null;
        }).when(session).removeAttribute(org.mockito.ArgumentMatchers.any(SessionKey.class));
        return session;
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
