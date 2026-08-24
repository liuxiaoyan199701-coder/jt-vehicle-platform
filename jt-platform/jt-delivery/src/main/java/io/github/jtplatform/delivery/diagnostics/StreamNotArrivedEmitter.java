package io.github.jtplatform.delivery.diagnostics;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.port.StreamNotArrivedListener;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把「开流后无流到达」发成连接类诊断事件，与网关的连接事件共用同一条投递与落库通道。
 *
 * <p>合用 {@link MessageType#CONNECTION} 而不另立类型：消费方（体检、事件查询）、
 * 租户归因与保留期语义与连接事件完全一致，按 kind 区分即可，分表只会让查询要做联表。
 */
public final class StreamNotArrivedEmitter implements StreamNotArrivedListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamNotArrivedEmitter.class);
    private static final long SYNTHETIC_MESSAGE_ID = 0x10002L;
    private static final String PROTOCOL_VERSION = "diagnostics-v1";

    private final MessagePublisher publisher;
    private final Clock clock;
    private final String instanceId;

    public StreamNotArrivedEmitter(MessagePublisher publisher, Clock clock, String instanceId) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instanceId = requireText(instanceId, "instanceId");
    }

    @Override
    public void onStreamNotArrived(StreamKey streamKey, String mediaInstanceId, long waitedMillis) {
        Objects.requireNonNull(streamKey, "streamKey");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("channel", streamKey.channel());
        detail.put("streamKind", streamKey.streamKind().wireValue());
        detail.put("waitedMs", Math.max(0, waitedMillis));
        if (mediaInstanceId != null && !mediaInstanceId.isBlank()) {
            detail.put("mediaInstanceId", mediaInstanceId.trim());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "STREAM_NOT_ARRIVED");
        payload.put("deviceId", streamKey.deviceId());
        payload.put("reason", "开流后未收到码流");
        payload.put("repeatCount", 1);
        payload.put("eventTime", clock.instant().toString());
        payload.put("detail", detail);
        try {
            publisher.publish(MessageEnvelope.create(streamKey.deviceId(), SYNTHETIC_MESSAGE_ID, 0,
                    PROTOCOL_VERSION, clock.instant(), instanceId, MessageType.CONNECTION, payload));
        } catch (RuntimeException failure) {
            LOGGER.warn("无流到达事件投递失败：stream={}", streamKey.externalId(), failure);
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
