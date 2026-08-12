package io.github.jtplatform.media.recording;

import io.github.jtplatform.delivery.listener.MessageEnvelopeListener;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.pipeline.MediaPipeline;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AlarmRecordingMessageListener implements MessageEnvelopeListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmRecordingMessageListener.class);

    private final RecordingProperties properties;
    private final RecordSink recordSink;
    private final MediaPipeline pipeline;

    public AlarmRecordingMessageListener(
            RecordingProperties properties,
            RecordSink recordSink,
            MediaPipeline pipeline) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.recordSink = Objects.requireNonNull(recordSink, "recordSink");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    @Override
    public void onMessage(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (!properties.isAlarmEnabled() || envelope.type() != MessageType.ALARM) {
            return;
        }
        AtomicInteger triggered = new AtomicInteger();
        for (var streamKey : pipeline.activeStreams()) {
            if (!streamKey.deviceId().equals(envelope.deviceId())) {
                continue;
            }
            pipeline.runIfActive(streamKey, () -> {
                if (recordSink.triggerAlarm(streamKey)) {
                    triggered.incrementAndGet();
                }
            });
        }
        if (triggered.get() > 0) {
            LOGGER.info("Alarm-linked recording started: device={}, streams={}, eventId={}",
                    envelope.deviceId(), triggered.get(), envelope.eventId());
        } else {
            LOGGER.debug("Alarm-linked recording found no active recordable stream: device={}, eventId={}",
                    envelope.deviceId(), envelope.eventId());
        }
    }
}
