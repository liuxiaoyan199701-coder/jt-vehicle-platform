package org.yzh.web.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtplatform.common.port.InMemoryDeviceRouter;
import io.github.jtplatform.signal.command.RecordingUploadCommandController;
import io.github.jtplatform.signal.diagnostics.ConnectionEventEmitter;
import io.github.yezhihao.netmc.session.Session;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t808.T0001;
import org.yzh.web.controller.JT1078Controller;
import org.yzh.web.controller.JT808Controller;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

class SignalEndpointCoverageTest {
    @Test
    void retainsEveryMigratedDownlinkRoute() {
        assertEquals(37, postMappingCount(JT808Controller.class));
        assertEquals(15, postMappingCount(JT1078Controller.class)
                + postMappingCount(RecordingUploadCommandController.class));
    }

    @Test
    void streamRoutesDoNotAcceptCallerSuppliedMediaTargets() {
        assertEquals(List.of("deviceId", "channel", "streamKind"),
                recordComponentNames(JT1078Controller.LiveCommand.class));
        assertEquals(List.of("deviceId", "channel", "startTime", "endTime"),
                recordComponentNames(JT1078Controller.PlaybackCommand.class));
        assertFalse(recordComponentNames(RecordingUploadCommandController.UploadCommand.class)
                .stream().anyMatch(name -> name.equals("username") || name.equals("password")
                        || name.equals("ip") || name.equals("port")));
    }

    @Test
    void sessionLifecycleBindsAndUnbindsDeviceRoute() {
        InMemoryDeviceRouter router = new InMemoryDeviceRouter();
        JTSessionListener listener = new JTSessionListener(router, "signal-1", new CommandResponseTracker());
        Session session = mock(Session.class);
        DeviceDO device = new DeviceDO()
                .setDeviceId("terminal-1")
                .setMobileNo("138000000000");
        when(session.<DeviceDO>getAttribute(SessionKey.Device)).thenReturn(device);

        listener.sessionRegistered(session);
        assertEquals("signal-1", router.findSignalInstance("terminal-1").orElseThrow());
        assertEquals("signal-1", router.findSignalInstance("138000000000").orElseThrow());

        listener.sessionDestroyed(session);
        assertFalse(router.findSignalInstance("terminal-1").isPresent());
        assertFalse(router.findSignalInstance("138000000000").isPresent());
    }

    @Test
    void replacingARegisteredSessionEmitsSessionReplacedEvent() {
        InMemoryDeviceRouter router = new InMemoryDeviceRouter();
        ConnectionEventEmitter diagnostics = mock(ConnectionEventEmitter.class);
        JTSessionListener listener = new JTSessionListener(
                router, "signal-1", new CommandResponseTracker(), diagnostics);
        Session first = session("terminal-1", "138000000000");
        Session replacement = session("terminal-1", "138000000000");
        when(first.getId()).thenReturn("session-1");
        when(replacement.getId()).thenReturn("session-2");
        when(replacement.getRemoteAddressStr()).thenReturn("127.0.0.1:8080");

        listener.sessionRegistered(first);
        listener.sessionRegistered(replacement);

        // 顶替判定按终端 ID（netmc 的会话就是用它注册的），但事件按手机号归户。
        verify(diagnostics).sessionReplaced(
                new io.github.jtplatform.signal.session.DeviceIdentity("138000000000", "terminal-1"),
                "127.0.0.1:8080", "被新会话顶替");
    }

    @Test
    void staleSessionDestructionDoesNotRemoveNewerAliasBindings() {
        InMemoryDeviceRouter router = new InMemoryDeviceRouter();
        JTSessionListener first = new JTSessionListener(router, "signal-1", new CommandResponseTracker());
        JTSessionListener second = new JTSessionListener(router, "signal-2", new CommandResponseTracker());
        Session firstSession = session("terminal-1", "138000000000");
        Session secondSession = session("terminal-1", "138000000000");

        first.sessionRegistered(firstSession);
        second.sessionRegistered(secondSession);
        first.sessionDestroyed(firstSession);

        assertEquals("signal-2", router.findSignalInstance("terminal-1").orElseThrow());
        assertEquals("signal-2", router.findSignalInstance("138000000000").orElseThrow());

        second.sessionDestroyed(secondSession);
        assertFalse(router.findSignalInstance("terminal-1").isPresent());
        assertFalse(router.findSignalInstance("138000000000").isPresent());
    }

    @Test
    void unknownMessageGetsNotSupportedResponseWithoutInvalidatingSession() {
        JTHandlerInterceptor interceptor = new JTHandlerInterceptor();
        Session session = mock(Session.class);
        when(session.nextSerialNo()).thenReturn(9);
        JTMessage unknown = new JTMessage()
                .setMessageId(0x7fff)
                .setClientId("123456789012")
                .setSerialNo(3);

        T0001 response = assertInstanceOf(T0001.class, interceptor.notSupported(unknown, session));

        assertEquals(T0001.NotSupport, response.getResultCode());
        assertEquals(0x7fff, response.getResponseMessageId());
        assertEquals(3, response.getResponseSerialNo());
        verify(session, never()).invalidate();
    }

    private static long postMappingCount(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .count();
    }

    private static List<String> recordComponentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
    }

    private static Session session(String terminalId, String mobileNo) {
        Session session = mock(Session.class);
        DeviceDO device = new DeviceDO().setDeviceId(terminalId).setMobileNo(mobileNo);
        when(session.<DeviceDO>getAttribute(SessionKey.Device)).thenReturn(device);
        return session;
    }
}
