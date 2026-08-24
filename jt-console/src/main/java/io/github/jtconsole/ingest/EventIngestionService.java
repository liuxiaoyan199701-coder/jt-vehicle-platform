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
    private final DeviceLogIngestionService deviceLogs;

    /** 测试便利构造：不带连接事件投影（连接类信封会走常规路径被忽略）。 */
    public EventIngestionService(
            EventRepository events, LocationService locations, MediaIngestionService media,
            DriverIdentityIngestionService driverIdentity, WaybillIngestionService waybills,
            RecordingUploadIngestionService recordingUploads) {
        this(events, locations, media, driverIdentity, waybills, recordingUploads, null, null);
    }

    /** 测试便利构造：带连接事件投影但不带日志库（日志类信封会被静默忽略）。 */
    public EventIngestionService(
            EventRepository events, LocationService locations, MediaIngestionService media,
            DriverIdentityIngestionService driverIdentity, WaybillIngestionService waybills,
            RecordingUploadIngestionService recordingUploads,
            ConnectionEventIngestionService connections) {
        this(events, locations, media, driverIdentity, waybills, recordingUploads, connections, null);
    }

    @Autowired
    public EventIngestionService(
            EventRepository events, LocationService locations, MediaIngestionService media,
            DriverIdentityIngestionService driverIdentity, WaybillIngestionService waybills,
            RecordingUploadIngestionService recordingUploads,
            ConnectionEventIngestionService connections,
            DeviceLogIngestionService deviceLogs) {
        this.events = events;
        this.locations = locations;
        this.media = media;
        this.driverIdentity = driverIdentity;
        this.waybills = waybills;
        this.recordingUploads = recordingUploads;
        this.connections = connections;
        this.deviceLogs = deviceLogs;
    }

    @Transactional
    public IngestionResult ingest(MessageEnvelope envelope) {
        validate(envelope);
        MessageEnvelope normalized = normalize(envelope);
        // 报文日志在 markProcessed 之前就分流走：它与业务信封同量级，走原链路等于把一半的
        // 写压转嫁给业务库那把唯一的写锁，日志库物理隔离的意义会被这一次写全部抵消。
        // 幂等改由日志库的 event_id 唯一索引 + INSERT OR IGNORE 承担。
        if (deviceLogs != null && deviceLogs.handle(normalized)) {
            return new IngestionResult("committed", "device-log", null);
        }
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
