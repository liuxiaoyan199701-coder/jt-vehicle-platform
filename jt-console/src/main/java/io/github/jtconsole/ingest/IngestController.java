package io.github.jtconsole.ingest;

import io.github.jtconsole.live.LiveBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收 jt-platform 网关的协议消息投递。
 *
 * <p>网关侧的约束决定了这个端点的写法：
 * <ul>
 *   <li>返回 2xx 即视为投递成功，响应体不会被读取，所以统一返回 204</li>
 *   <li>非 2xx 会触发重试（location/heartbeat 除外，它们直接被丢弃）</li>
 *   <li>投递有 10 秒超时，且同一设备的消息在网关侧是串行的——这里任何阻塞都会
 *       反压到网关并导致该设备的位置点被丢弃，因此不做任何慢操作</li>
 *   <li>同一 eventId 可能被重复投递，必须去重</li>
 * </ul>
 *
 * <p>控制器只在事务服务成功返回后记录提交结果并提交实时广播。存储异常保持向上抛出形成
 * 5xx，使网关可以重试；确定性的信封校验错误返回 400，且不会占用 eventId。
 */
@RestController
@RequestMapping("/ingest")
public class IngestController {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestController.class);

    private final EventIngestionService ingestion;
    private final RecentEventLog recentEvents;
    private final LiveBroadcaster broadcaster;

    public IngestController(
            EventIngestionService ingestion,
            RecentEventLog recentEvents,
            LiveBroadcaster broadcaster) {
        this.ingestion = ingestion;
        this.recentEvents = recentEvents;
        this.broadcaster = broadcaster;
    }

    @PostMapping("/jt-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@RequestBody MessageEnvelope envelope) {
        IngestionResult result;
        try {
            result = ingestion.ingest(envelope);
        } catch (InvalidEnvelopeException invalid) {
            recentEvents.record(envelope, "validation-rejected", "invalid-envelope");
            LOGGER.warn("Rejected event envelope: eventId={}, deviceId={}, messageId={}, reason={}",
                    eventId(envelope), deviceId(envelope), messageId(envelope), invalid.getMessage());
            throw invalid;
        } catch (RuntimeException storageFailure) {
            recentEvents.record(envelope, "transaction-failed", "storage-error");
            LOGGER.error("Event transaction failed: eventId={}, deviceId={}, messageId={}",
                    eventId(envelope), deviceId(envelope), messageId(envelope), storageFailure);
            throw storageFailure;
        }

        recentEvents.record(envelope, result.result(), result.outcome());
        if (result.liveUpdate() != null && !broadcaster.publish(result.liveUpdate())) {
            LOGGER.warn("Live update queue rejected event: eventId={}, deviceId={}, messageId={}",
                    eventId(envelope), deviceId(envelope), messageId(envelope));
        }
    }

    @ExceptionHandler(InvalidEnvelopeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void invalidEnvelope() {
        // The validation detail is logged server-side without echoing payloads or credentials.
    }

    private static String eventId(MessageEnvelope envelope) {
        return envelope == null ? null : envelope.eventId();
    }

    private static String deviceId(MessageEnvelope envelope) {
        return envelope == null ? null : envelope.deviceId();
    }

    private static Long messageId(MessageEnvelope envelope) {
        return envelope == null ? null : envelope.messageId();
    }
}
