package io.github.jtplatform.signal.session;

import io.github.yezhihao.netmc.session.Session;
import java.util.Optional;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

/**
 * 一台终端在平台上的身份，全网关唯一的解析口径。
 *
 * <p><b>为什么要有这个类</b>——JT/T 808 的一台终端同时有两个标识，而它们长得完全不像：
 *
 * <ul>
 *   <li>{@code canonical}：终端手机号（SIM 号），来自报文头的 clientId，例如 {@code 138000000000}。
 *       <b>这是全平台的主键</b>：车辆档案 {@code vehicle.device_id}、轨迹、告警、设备日志
 *       全都按它归户。</li>
 *   <li>{@code terminalId}：0x0100 注册正文里终端自报的「终端 ID」，7 字节，例如 {@code 1380000}。
 *       它只是终端出厂时写死的一串编号，<b>平台侧没有任何一张表按它建键</b>。</li>
 * </ul>
 *
 * <p>连接诊断事件曾经按终端 ID 发出，而位置、报文日志按手机号——同一台设备在
 * {@code connection_event} 里是一个键、在别处是另一个键。后果是静默的：按车辆设备号查连接
 * 记录永远为空（体检的连接维度因此一直判不出结论），租户归因查不到而记 NULL
 * （租户用户一条上下线都看不到），而接口全程 200。2026-08-24 生产库上实测到同一张表里
 * COMMAND_RESULT 用手机号、CONNECTED 用终端 ID，连 AUTH_RESULT 都因两条代码路径而两种都有。
 *
 * <p>所以身份解析必须只有一处，事件一律用 {@link #canonical()} 发出；终端 ID 不丢弃，
 * 作为诊断附注随事件带走——它是「终端换了但 SIM 没换」这类问题的唯一线索。
 */
public record DeviceIdentity(String canonical, String terminalId) {

    public DeviceIdentity {
        canonical = normalize(canonical);
        terminalId = normalize(terminalId);
    }

    /**
     * 从会话与当前报文解析身份。
     *
     * <p>手机号的三个来源按可信度排序：已鉴权设备的档案 → 本条报文的报文头 →
     * 注册时留在会话上的副本（注册被拒时也留，否则那台设备后续的断开与协议错误就全成了匿名事件）。
     *
     * @param message 可为 null（会话级事件，如连接建立与断开）
     * @return 手机号解析不出时为空——调用方自行决定是跳过还是记成匿名，本类不替它决定
     */
    public static Optional<DeviceIdentity> resolve(Session session, JTMessage message) {
        String terminalId = terminalId(session);
        DeviceDO device = session == null ? null : session.getAttribute(SessionKey.Device);
        if (device != null && hasText(device.getMobileNo())) {
            return Optional.of(new DeviceIdentity(device.getMobileNo(),
                    terminalId != null ? terminalId : device.getDeviceId()));
        }
        if (message != null && hasText(message.getClientId())) {
            return Optional.of(new DeviceIdentity(message.getClientId(), terminalId));
        }
        String remembered = session == null
                ? null
                : session.getAttribute(SessionKey.DiagnosticMobileNo);
        if (hasText(remembered)) {
            return Optional.of(new DeviceIdentity(remembered, terminalId));
        }
        return Optional.empty();
    }

    public static Optional<DeviceIdentity> resolve(Session session) {
        return resolve(session, null);
    }

    /**
     * 解析不出手机号时退回一个匿名身份。
     *
     * <p>只给报文日志用：一帧连身份都认不出来的畸形数据，恰恰最需要留下原始字节，
     * 丢掉它比记成 {@code unknown} 糟糕得多。诊断事件不用这个——匿名的连接事件没有排查价值，
     * 反而会在时间线上堆出一串归不到任何设备的噪声。
     */
    public static DeviceIdentity resolveOrUnknown(Session session, JTMessage message) {
        return resolve(session, message)
                .orElseGet(() -> new DeviceIdentity("unknown", terminalId(session)));
    }

    /** 终端自报的终端 ID；注册被拒时同样留存，用于把后续事件与那次注册对上。 */
    private static String terminalId(Session session) {
        if (session == null) {
            return null;
        }
        String diagnostic = session.getAttribute(SessionKey.DiagnosticDeviceId);
        if (hasText(diagnostic)) {
            return diagnostic;
        }
        DeviceDO device = session.getAttribute(SessionKey.Device);
        return device == null ? null : normalize(device.getDeviceId());
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
