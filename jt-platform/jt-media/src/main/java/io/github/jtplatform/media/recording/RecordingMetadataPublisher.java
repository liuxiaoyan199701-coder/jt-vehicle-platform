package io.github.jtplatform.media.recording;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RecordingMetadataPublisher implements RecordingSegmentListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingMetadataPublisher.class);
    private static final long LOCAL_RECORDING_EVENT_ID = 0xF100L;

    private final MessagePublisher publisher;
    private final String instanceId;
    private final Clock clock;

    public RecordingMetadataPublisher(MessagePublisher publisher, String instanceId, Clock clock) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.instanceId = requiredText(instanceId, "instanceId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onCommitted(RecordingSegmentMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", metadata.streamKey().channel());
        payload.put("streamKind", metadata.streamKey().streamKind().wireValue());
        payload.put("startTimestampUs", metadata.startTimestampUs());
        payload.put("endTimestampUs", metadata.endTimestampUs());
        payload.put("frameCount", metadata.frameCount());
        payload.put("keyFrameCount", metadata.keyFrameCount());
        payload.put("dataBytes", metadata.dataBytes());
        payload.put("indexBytes", metadata.indexBytes());
        payload.put("dataPath", metadata.dataPath().toString());
        payload.put("indexPath", metadata.indexPath().toString());
        payload.put("markerPath", metadata.markerPath().toString());

        MessageEnvelope envelope = MessageEnvelope.create(
                metadata.streamKey().deviceId(),
                LOCAL_RECORDING_EVENT_ID,
                0,
                "recording-v1",
                clock.instant(),
                instanceId,
                MessageType.RECORDING_METADATA,
                payload);
        PublishResult result = publisher.publish(envelope);
        if (result.channels().containsValue(PublishDisposition.RETRY_REQUIRED)) {
            LOGGER.error("Recording metadata needs resubmission: eventId={}, segment={}, result={}",
                    envelope.eventId(), metadata.markerPath(), result.channels());
        } else if (!result.acceptedByEveryEnabledChannel()) {
            LOGGER.warn("Recording metadata was not accepted by every enabled channel: eventId={}, result={}",
                    envelope.eventId(), result.channels());
        }
    }

    private static String requiredText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
