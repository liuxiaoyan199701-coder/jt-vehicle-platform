package io.github.jtplatform.signal.delivery;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.signal.session.DeviceIdentity;
import io.github.yezhihao.netmc.session.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t1078.T1206;
import org.yzh.protocol.t808.T0701;

public final class SignalMessageEnvelopeMapper {
    private final ProtocolPayloadMapper payloadMapper;
    private final MessageTypeClassifier typeClassifier;
    private final Clock clock;
    private final String instanceId;

    public SignalMessageEnvelopeMapper(
            ProtocolPayloadMapper payloadMapper,
            MessageTypeClassifier typeClassifier,
            Clock clock,
            String instanceId) {
        this.payloadMapper = Objects.requireNonNull(payloadMapper, "payloadMapper");
        this.typeClassifier = Objects.requireNonNull(typeClassifier, "typeClassifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        this.instanceId = instanceId;
    }

    public MessageEnvelope map(Session session, JTMessage message) {
        return map(session, message, Map.of());
    }

    public MessageEnvelope map(Session session, JTMessage message, Map<String, ?> additionalPayload) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(additionalPayload, "additionalPayload");
        Map<String, Object> mappedPayload = payloadMapper.map(message);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>(mappedPayload);
        additionalPayload.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("additional payload keys must not be blank");
            }
            payload.put(key, value);
        });
        String deviceId = resolveDeviceId(session, message);
        if (message instanceof T1206 completed) {
            return new MessageEnvelope(
                    "recording-upload-complete:" + deviceId + ':'
                            + completed.getResponseSerialNo() + ':' + completed.getResult(),
                    deviceId,
                    Integer.toUnsignedLong(message.getMessageId()),
                    message.getSerialNo(),
                    protocolVersion(message),
                    clock.instant(),
                    instanceId,
                    typeClassifier.classify(message),
                    payload);
        }
        if (message instanceof T0701 waybill) {
            byte[] data = waybill.getData() == null ? new byte[0] : waybill.getData();
            payload.put("rawBase64", Base64.getEncoder().encodeToString(data));
            payload.put("length", data.length);
            return new MessageEnvelope(
                    waybillEventId(deviceId, message.getSerialNo(), data),
                    deviceId,
                    Integer.toUnsignedLong(message.getMessageId()),
                    message.getSerialNo(),
                    protocolVersion(message),
                    clock.instant(),
                    instanceId,
                    typeClassifier.classify(message),
                    payload);
        }
        return MessageEnvelope.create(
                deviceId,
                Integer.toUnsignedLong(message.getMessageId()),
                message.getSerialNo(),
                protocolVersion(message),
                clock.instant(),
                instanceId,
                typeClassifier.classify(message),
                payload);
    }

    /** 身份解析全网关只有一处，见 {@link DeviceIdentity}；业务信封这一路解析不出就拒绝投递。 */
    private static String resolveDeviceId(Session session, JTMessage message) {
        return DeviceIdentity.resolve(session, message)
                .map(DeviceIdentity::canonical)
                .orElseThrow(() -> new IllegalArgumentException(
                        "message does not provide a canonical mobileNo/SIM identity"));
    }

    private static String protocolVersion(JTMessage message) {
        if (message.isVersion() || message.getProtocolVersion() > 0) {
            return "JT/T 808-2019/" + message.getProtocolVersion();
        }
        return "JT/T 808-2013";
    }

    /**
     * 0701 使用稳定幂等键：设备 + 消息流水号 + 原文摘要。
     *
     * <p>流水号满足协议重发关联；摘要避免终端重启后复用同一流水号时误吞一张不同运单。
     */
    private static String waybillEventId(String deviceId, int serialNo, byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(deviceId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) (serialNo >>> 8));
            digest.update((byte) serialNo);
            digest.update(data);
            return "waybill:" + deviceId + ':' + serialNo + ':'
                    + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

}
