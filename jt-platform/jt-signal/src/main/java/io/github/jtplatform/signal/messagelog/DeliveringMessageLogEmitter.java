package io.github.jtplatform.signal.messagelog;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.signal.delivery.ProtocolPayloadMapper;
import io.github.yezhihao.netmc.session.Session;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.MessageId;
import org.yzh.protocol.t1078.T9206;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;
import tools.jackson.databind.ObjectMapper;

/**
 * 走既有投递管道把报文日志发往控制台。
 *
 * <p>三条护栏与 {@code ConnectionEventEmitter} 同款：publish 包 try/catch 只 warn、
 * 采集失败不上抛、内容在网关侧就截断——超长报文不能既撑爆落盘 spool 又撑爆日志库。
 */
public final class DeliveringMessageLogEmitter implements MessageLogEmitter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveringMessageLogEmitter.class);
    /** 口令脱敏后的占位值。写成看得见的字样而不是空串，免得被当成「终端没填」。 */
    private static final String REDACTED = "***";

    private final MessagePublisher publisher;
    private final ProtocolPayloadMapper payloadMapper;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;
    private final String instanceId;
    private final int maxHexChars;
    private final int maxJsonChars;

    public DeliveringMessageLogEmitter(
            MessagePublisher publisher,
            ProtocolPayloadMapper payloadMapper,
            Clock clock,
            String instanceId,
            int maxHexChars,
            int maxJsonChars) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.payloadMapper = Objects.requireNonNull(payloadMapper, "payloadMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        this.instanceId = instanceId;
        this.maxHexChars = Math.max(64, maxHexChars);
        this.maxJsonChars = Math.max(64, maxJsonChars);
    }

    @Override
    public void inbound(Session session, JTMessage message, ByteBuf input) {
        if (message == null) {
            return;
        }
        try {
            emit(resolveDeviceId(session, message), Direction.UP, message, hex(input), false, null);
        } catch (RuntimeException failure) {
            LOGGER.warn("上行报文日志采集失败", failure);
        }
    }

    @Override
    public void outbound(Session session, JTMessage message, ByteBuf output) {
        if (message == null) {
            return;
        }
        try {
            emit(resolveDeviceId(session, message), Direction.DOWN, message, hex(output), false, null);
        } catch (RuntimeException failure) {
            LOGGER.warn("下行报文日志采集失败", failure);
        }
    }

    @Override
    public void decodeFailure(Session session, ByteBuf input, Throwable failure) {
        try {
            emit(sessionIdentity(session), Direction.UP, null, hex(input), true, failure);
        } catch (RuntimeException problem) {
            LOGGER.warn("解码失败帧的日志采集失败", problem);
        }
    }

    /**
     * @param message 解码失败时为 null——此时消息 ID、流水号都无从得知，只有原始字节可留
     */
    private void emit(
            String deviceId, Direction direction, JTMessage message,
            Truncated rawHex, boolean decodeError, Throwable failure) {
        boolean redact = message instanceof T9206;
        Truncated parsedJson = message == null ? Truncated.absent() : parse(message, redact);
        Instant now = clock.instant();
        int messageId = message == null ? 0 : message.getMessageId();
        int serialNo = message == null ? 0 : message.getSerialNo();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("direction", direction.name());
        payload.put("msgIdHex", message == null ? "" : String.format("0x%04X", messageId));
        payload.put("serialNo", Integer.toString(serialNo));
        payload.put("summary", summary(message, decodeError, failure, redact));
        // 9206 正文含一次性 FTP 口令，原始帧里的明文没法只抹掉一段，整条 hex 都不留。
        payload.put("rawHex", redact ? "" : rawHex.text());
        payload.put("parsedJson", parsedJson.text());
        payload.put("decodeError", decodeError ? "1" : "0");
        payload.put("truncated", (!redact && rawHex.truncated()) || parsedJson.truncated() ? "1" : "0");
        payload.put("logTime", now.toString());

        MessageEnvelope envelope = new MessageEnvelope(
                UUID.randomUUID().toString(), deviceId,
                Integer.toUnsignedLong(messageId), Math.floorMod(serialNo, 0x10000),
                protocolVersion(message), now, instanceId, MessageType.DEVICE_LOG, payload);
        try {
            publisher.publish(envelope);
        } catch (RuntimeException problem) {
            LOGGER.warn("报文日志投递失败：device={}, direction={}", deviceId, direction, problem);
        }
    }

    private Truncated parse(JTMessage message, boolean redact) {
        try {
            Map<String, Object> mapped = new LinkedHashMap<>(payloadMapper.map(message));
            if (redact) {
                // 脱敏收敛在这一处：raw hex 已整条丢弃，解析结果里只需抹掉两个字段。
                mapped.replaceAll((key, value) ->
                        "username".equals(key) || "password".equals(key) ? REDACTED : value);
            }
            return Truncated.of(json.writeValueAsString(mapped), maxJsonChars);
        } catch (RuntimeException failure) {
            // 解析结果拿不到不该连累原始 hex——后者才是排障时真正不可再生的证据。
            LOGGER.debug("报文解析结果无法序列化，按空处理", failure);
            return Truncated.absent();
        }
    }

    private Truncated hex(ByteBuf buffer) {
        if (buffer == null || buffer.writerIndex() <= 0) {
            return Truncated.absent();
        }
        // 必须在钩子内同步拷贝：返回后这块 ByteBuf 立即被释放。
        return Truncated.of(ByteBufUtil.hexDump(buffer, 0, buffer.writerIndex()), maxHexChars);
    }

    private static String summary(
            JTMessage message, boolean decodeError, Throwable failure, boolean redact) {
        if (message == null) {
            String reason = failure == null ? "未知原因" : failure.getClass().getSimpleName();
            return "解码失败：" + reason;
        }
        StringBuilder text = new StringBuilder(MessageId.getName(message.getMessageId()));
        if (message.isSubpackage()) {
            // 组包完成时 input 只剩最后一个物理帧，不说明白会被当成「报文怎么这么短」。
            text.append("（分包 ").append(message.getPackageNo()).append('/')
                    .append(message.getPackageTotal()).append(" 包，原始 hex 仅含末包）");
        }
        if (redact) {
            text.append("（含 FTP 一次性口令，原始报文与口令字段已脱敏）");
        }
        if (decodeError) {
            text.insert(0, "解码失败：");
        }
        return text.toString();
    }

    private static String protocolVersion(JTMessage message) {
        if (message != null && (message.isVersion() || message.getProtocolVersion() > 0)) {
            return "JT/T 808-2019/" + message.getProtocolVersion();
        }
        return "JT/T 808-2013";
    }

    /**
     * 日志的设备身份可以不完美，但绝不能因为查不到就抛异常——
     * {@code SignalMessageEnvelopeMapper} 那套「解析不出就拒绝投递」的严格口径在这里是反的：
     * 身份不明的帧恰恰最需要留证。
     */
    private static String resolveDeviceId(Session session, JTMessage message) {
        if (session != null) {
            DeviceDO device = session.getAttribute(SessionKey.Device);
            if (device != null && hasText(device.getMobileNo())) {
                return device.getMobileNo();
            }
        }
        if (message != null && hasText(message.getClientId())) {
            return message.getClientId();
        }
        return sessionIdentity(session);
    }

    private static String sessionIdentity(Session session) {
        if (session == null) {
            return "unknown";
        }
        DeviceDO device = session.getAttribute(SessionKey.Device);
        if (device != null && hasText(device.getMobileNo())) {
            return device.getMobileNo();
        }
        String diagnostic = session.getAttribute(SessionKey.DiagnosticDeviceId);
        if (hasText(diagnostic)) {
            return diagnostic;
        }
        return hasText(session.getClientId()) ? session.getClientId() : "unknown";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private enum Direction { UP, DOWN }

    /** 截断后的文本与「是否发生了截断」。空内容用空串而不是 null，信封 payload 因此形状恒定。 */
    private record Truncated(String text, boolean truncated) {
        static Truncated absent() {
            return new Truncated("", false);
        }

        static Truncated of(String text, int limit) {
            if (text == null) {
                return absent();
            }
            return text.length() <= limit
                    ? new Truncated(text, false)
                    : new Truncated(text.substring(0, limit), true);
        }
    }
}
