package io.github.jtplatform.signal.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yezhihao.netmc.session.Session;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0200;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

/**
 * 归户键只有一个：终端手机号。
 *
 * <p>终端 ID（{@code 1380000}）与手机号（{@code 138000000000}）长得完全不像，却在很长一段时间里
 * 被混用——生产库上实测到同一张 connection_event 里 COMMAND_RESULT 用手机号、CONNECTED 用终端 ID。
 * 混用不会报错，只会让事件静默地归不到车辆上，所以这里逐条钉住解析顺序。
 */
class DeviceIdentityTest {

    private static final String MOBILE = "138000000000";
    private static final String TERMINAL = "1380000";

    @Test
    void anAuthenticatedSessionResolvesToTheProfiledMobileNumber() {
        Session session = session(Map.of(
                SessionKey.Device, device(TERMINAL, MOBILE),
                SessionKey.DiagnosticDeviceId, TERMINAL));

        DeviceIdentity identity = DeviceIdentity.resolve(session).orElseThrow();

        assertThat(identity.canonical()).isEqualTo(MOBILE);
        assertThat(identity.terminalId()).isEqualTo(TERMINAL);
    }

    /** 注册那一刻会话上还没有 Device，手机号只能从报文头拿——0x0100 的报文头就是手机号。 */
    @Test
    void theRegistrationFrameItselfCarriesTheMobileNumberInItsHeader() {
        T0100 registration = new T0100();
        registration.setClientId(MOBILE);
        registration.setDeviceId(TERMINAL);
        Session session = session(Map.of(SessionKey.DiagnosticDeviceId, TERMINAL));

        DeviceIdentity identity = DeviceIdentity.resolve(session, registration).orElseThrow();

        assertThat(identity.canonical()).isEqualTo(MOBILE);
        assertThat(identity.terminalId()).isEqualTo(TERMINAL);
    }

    /**
     * 注册被拒的设备后续还会断开、还会报协议错误。会话上没有 Device、事件也没有报文，
     * 全靠注册时留下的手机号副本——没有它，那些事件就只剩终端 ID，永远归不到车辆。
     */
    @Test
    void aRejectedRegistrationStillLeavesEnoughToAttributeLaterEvents() {
        Session session = session(Map.of(
                SessionKey.DiagnosticDeviceId, TERMINAL,
                SessionKey.DiagnosticMobileNo, MOBILE));

        DeviceIdentity identity = DeviceIdentity.resolve(session).orElseThrow();

        assertThat(identity.canonical()).isEqualTo(MOBILE);
        assertThat(identity.terminalId()).isEqualTo(TERMINAL);
    }

    @Test
    void aProfiledDeviceWinsOverTheFrameHeader() {
        T0200 location = new T0200();
        location.setClientId("139999999999");
        Session session = session(Map.of(SessionKey.Device, device(TERMINAL, MOBILE)));

        assertThat(DeviceIdentity.resolve(session, location).orElseThrow().canonical())
                .isEqualTo(MOBILE);
    }

    /** 连接事件宁可不发也不能匿名发：一串归不到任何设备的事件只会在时间线上堆噪声。 */
    @Test
    void aBareConnectionResolvesToNothingSoNoEventIsEmitted() {
        assertThat(DeviceIdentity.resolve(session(Map.of()))).isEmpty();
        assertThat(DeviceIdentity.resolve(null)).isEmpty();
    }

    /** 报文日志反过来：认不出身份的畸形帧最需要留证，退回 unknown 也要记下来。 */
    @Test
    void anUnidentifiableFrameFallsBackToUnknownForTheMessageLog() {
        DeviceIdentity identity = DeviceIdentity.resolveOrUnknown(session(Map.of()), null);

        assertThat(identity.canonical()).isEqualTo("unknown");
        assertThat(identity.terminalId()).isNull();
    }

    @Test
    void blankValuesAreNormalizedAwayRatherThanBecomingAnEmptyKey() {
        DeviceIdentity identity = new DeviceIdentity("  " + MOBILE + " ", "   ");

        assertThat(identity.canonical()).isEqualTo(MOBILE);
        assertThat(identity.terminalId()).isNull();
    }

    private static DeviceDO device(String terminalId, String mobileNo) {
        return new DeviceDO().setDeviceId(terminalId).setMobileNo(mobileNo);
    }

    @SuppressWarnings("unchecked")
    private static Session session(Map<SessionKey, Object> attributes) {
        Map<SessionKey, Object> values = new HashMap<>(attributes);
        Session session = Mockito.mock(Session.class);
        Mockito.lenient().when(session.getAttribute(Mockito.any(SessionKey.class)))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        return session;
    }
}
