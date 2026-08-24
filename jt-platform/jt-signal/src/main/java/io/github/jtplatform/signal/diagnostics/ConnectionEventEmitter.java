package io.github.jtplatform.signal.diagnostics;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 发射结构化连接诊断事件；不改变终端连接处理，只走既有可靠投递通道。 */
public final class ConnectionEventEmitter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionEventEmitter.class);
    private static final long SYNTHETIC_MESSAGE_ID = 0x10001L;
    private static final Duration DEDUP_WINDOW = Duration.ofSeconds(60);
    private static final Duration PROTOCOL_WINDOW = Duration.ofHours(1);
    private static final int PROTOCOL_LIMIT = 60;

    private final MessagePublisher publisher;
    private final Clock clock;
    private final String instanceId;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<DedupKey, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProtocolWindow> protocolWindows = new ConcurrentHashMap<>();

    public ConnectionEventEmitter(MessagePublisher publisher, Clock clock, String instanceId) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instanceId = requireText(instanceId, "instanceId");
    }

    public void connected(String deviceId, String remoteAddress) {
        emit(deviceId, Kind.CONNECTED, null, null, remoteAddress, Map.of());
    }

    public void disconnected(String deviceId, String remoteAddress, Integer reasonCode, String reason) {
        emit(deviceId, Kind.DISCONNECTED, reasonCode, reason, remoteAddress, Map.of());
    }

    public void sessionReplaced(String deviceId, String remoteAddress, String reason) {
        emit(deviceId, Kind.SESSION_REPLACED, null, reason, remoteAddress, Map.of());
    }

    public void registerResult(
            String deviceId, int resultCode, String reason, String remoteAddress) {
        emit(deviceId, Kind.REGISTER_RESULT, resultCode, reason, remoteAddress, Map.of(
                "resultCode", resultCode));
    }

    public void authResult(String deviceId, boolean success, String reason, String remoteAddress) {
        emit(deviceId, Kind.AUTH_RESULT, success ? 0 : 1, reason, remoteAddress,
                Map.of("success", success));
    }

    /**
     * 下行指令的应答结局。挂钩点在所有「期待应答的下行」的收口处，新增指令自动获得覆盖。
     *
     * <p>去噪按（设备、指令消息 ID、结局）聚合：同一条指令反复超时不该刷屏，
     * 而同一设备的不同指令、或同一指令的不同结局都必须各自可见。
     */
    public void commandResult(
            String deviceId, long commandMessageId, CommandOutcome outcome,
            Integer resultCode, String remoteAddress) {
        Objects.requireNonNull(outcome, "outcome");
        String command = formatMessageId(commandMessageId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("commandMsgId", command);
        detail.put("outcome", outcome.name());
        if (resultCode != null) {
            detail.put("resultCode", resultCode);
        }
        emit(deviceId, Kind.COMMAND_RESULT, resultCode, command + ' ' + outcome.description(),
                remoteAddress, Map.of("detail", detail), command + '/' + outcome.name());
    }

    private static String formatMessageId(long messageId) {
        return String.format("0x%04X", messageId);
    }

    public void protocolError(String deviceId, String reason, String remoteAddress) {
        String key = requireText(deviceId, "deviceId");
        Instant now = clock.instant();
        ProtocolWindow window = protocolWindows.compute(key, (ignored, current) -> {
            if (current == null || now.isAfter(current.startedAt.plus(PROTOCOL_WINDOW))) {
                return new ProtocolWindow(now, 1);
            }
            return new ProtocolWindow(current.startedAt, current.count + 1);
        });
        if (window.count > PROTOCOL_LIMIT) {
            return;
        }
        emit(key, Kind.PROTOCOL_ERROR, null, reason, remoteAddress,
                Map.of("hourlyCount", window.count));
    }

    /** 用于测试和运维统计：只发出可查询的事件，不暴露内部窗口状态。 */
    public void clearNoiseWindows() {
        windows.clear();
        protocolWindows.clear();
    }

    private void emit(
            String deviceId, Kind kind, Integer reasonCode, String reason,
            String remoteAddress, Map<String, ?> extra) {
        emit(deviceId, kind, reasonCode, reason, remoteAddress, extra, null);
    }

    /**
     * @param dedupDiscriminator 参与去噪窗口的额外维度，为 null 时按「原因」聚合
     */
    private void emit(
            String deviceId, Kind kind, Integer reasonCode, String reason,
            String remoteAddress, Map<String, ?> extra, String dedupDiscriminator) {
        String device = requireText(deviceId, "deviceId");
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
        Instant now = clock.instant();
        DedupKey key = new DedupKey(device, kind, reasonCode, normalizedReason, dedupDiscriminator);
        int[] emittedCount = {0};
        windows.compute(key, (ignored, current) -> {
            if (current == null) {
                emittedCount[0] = 1;
                return new Window(now, 1);
            }
            if (!now.isBefore(current.startedAt.plus(DEDUP_WINDOW))) {
                // 窗口结束后的首条承载上一窗口累计数与本条；新窗口从本条重新计数，避免永久累加。
                emittedCount[0] = current.repeatCount + 1;
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.repeatCount + 1);
        });
        if (emittedCount[0] == 0) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind.name());
        payload.put("deviceId", device);
        if (remoteAddress != null) {
            payload.put("remoteAddr", remoteAddress);
        }
        if (reasonCode != null) {
            payload.put("reasonCode", reasonCode);
        }
        if (normalizedReason != null) {
            payload.put("reason", normalizedReason);
        }
        payload.put("repeatCount", emittedCount[0]);
        payload.put("eventTime", now.toString());
        extra.forEach(payload::put);
        MessageEnvelope envelope = new MessageEnvelope(
                UUID.randomUUID().toString(), device, SYNTHETIC_MESSAGE_ID,
                Math.floorMod(sequence.getAndIncrement(), 0x10000), "JT/T 808-2019",
                now, instanceId, MessageType.CONNECTION, payload);
        try {
            publisher.publish(envelope);
        } catch (RuntimeException failure) {
            LOGGER.warn("连接诊断事件投递失败：device={}, kind={}", device, kind, failure);
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public enum Kind {
        CONNECTED, DISCONNECTED, REGISTER_RESULT, AUTH_RESULT, PROTOCOL_ERROR, SESSION_REPLACED,
        COMMAND_RESULT
    }

    /** 下行指令的四类结局，另加「下发失败」以区分「送不出去」与「送出后没回音」。 */
    public enum CommandOutcome {
        OK("终端应答成功"),
        REJECTED("终端拒绝指令"),
        TIMEOUT("终端未应答（超时）"),
        OFFLINE("设备离线，指令未下发"),
        FAILED("指令下发失败");

        private final String description;

        CommandOutcome(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private record DedupKey(
            String deviceId, Kind kind, Integer reasonCode, String reason, String discriminator) {}
    private record Window(Instant startedAt, int repeatCount) {}
    private record ProtocolWindow(Instant startedAt, int count) {}
}
