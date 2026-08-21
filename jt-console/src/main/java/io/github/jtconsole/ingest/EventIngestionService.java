package io.github.jtconsole.ingest;

import io.github.jtconsole.repository.EventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies idempotency and business projections as one atomic database operation. */
@Service
public class EventIngestionService {

    private final EventRepository events;
    private final LocationService locations;
    private final MediaIngestionService media;
    private final DriverIdentityIngestionService driverIdentity;
    private final WaybillIngestionService waybills;
    private final RecordingUploadIngestionService recordingUploads;
    private final ConnectionEventIngestionService connections;

    /** 测试便利构造：不带连接事件投影（连接类信封会走常规路径被忽略）。 */
    public EventIngestionService(
            EventRepository events, LocationService locations, MediaIngestionService media,
            DriverIdentityIngestionService driverIdentity, WaybillIngestionService waybills,
            RecordingUploadIngestionService recordingUploads) {
        this(events, locations, media, driverIdentity, waybills, recordingUploads, null);
    }

    @Autowired
    public EventIngestionService(
            EventRepository events, LocationService locations, MediaIngestionService media,
            DriverIdentityIngestionService driverIdentity, WaybillIngestionService waybills,
            RecordingUploadIngestionService recordingUploads,
            ConnectionEventIngestionService connections) {
        this.events = events;
        this.locations = locations;
        this.media = media;
        this.driverIdentity = driverIdentity;
        this.waybills = waybills;
        this.recordingUploads = recordingUploads;
        this.connections = connections;
    }

    @Transactional
    public IngestionResult ingest(MessageEnvelope envelope) {
        validate(envelope);
        MessageEnvelope normalized = normalize(envelope);
        if (!events.markProcessed(normalized.eventId())) {
            return IngestionResult.duplicate();
        }

        if (connections != null && connections.handle(normalized)) {
            return new IngestionResult("committed", "connection", null);
        }
        // 多媒体上传先落元数据，再走位置/在线时间的常规处理
        media.handleIfMediaUpload(normalized);
        // 驾驶员身份识别（0702）落事件与驾驶区间
        driverIdentity.handleIfDriverIdentity(normalized);
        // 电子运单（0701）按归属无损留存原文
        waybills.handleIfWaybill(normalized);
        // 录像上传完成（1206）与媒体节点文件到达事件投影到同一任务状态机
        recordingUploads.handle(normalized);

        LocationHandlingResult handled = locations.handle(normalized);
        return IngestionResult.committed(handled);
    }

    private static void validate(MessageEnvelope envelope) {
        if (envelope == null) {
            throw new InvalidEnvelopeException("request body must not be empty");
        }
        if (envelope.eventId() == null || envelope.eventId().isBlank()) {
            throw new InvalidEnvelopeException("eventId must not be blank");
        }
        if (envelope.deviceId() == null || envelope.deviceId().isBlank()) {
            throw new InvalidEnvelopeException("deviceId must not be blank");
        }
        if (envelope.messageId() == null) {
            throw new InvalidEnvelopeException("messageId must not be null");
        }
    }

    private static MessageEnvelope normalize(MessageEnvelope envelope) {
        return new MessageEnvelope(
                envelope.eventId().trim(),
                envelope.deviceId().trim(),
                envelope.messageId(),
                envelope.serialNo(),
                envelope.protocolVersion(),
                envelope.receivedAt(),
                envelope.instanceId(),
                envelope.type(),
                envelope.payload());
    }
}
