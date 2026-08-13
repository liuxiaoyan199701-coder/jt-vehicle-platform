package io.github.jtconsole.ingest;

import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.repository.MediaRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把终端上传的多媒体文件元数据（0x0801 投递信封）持久化。
 *
 * <p>二进制文件本身由网关落盘（{@code /var/lib/jt-platform/data/signal/multimedia}），
 * 投递信封里带过来的是 {@code fileId / fileType / fileFormat / fileName / size /
 * accessAddress} 等标识（见网关 {@code StoredMediaFile.deliveryMetadata()}）。
 * 这里只落元数据，供「远程控制面板」的拍照结果列表展示。
 *
 * <p>注意网关的 {@code MessageTypeClassifier} 没有把 0x0801 归类为 {@code multimedia}，
 * 它的 {@code type} 是 {@code "other"}——因此按 {@code messageId == 2049} 判断，
 * 不按 {@code type} 判断。
 */
@Service
public class MediaIngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaIngestionService.class);

    /** JT/T 808 多媒体数据上传 0x0801 */
    private static final long MULTIMEDIA_UPLOAD = 0x0801L;

    private final MediaRepository media;

    public MediaIngestionService(MediaRepository media) {
        this.media = media;
    }

    /**
     * @return true 表示这条消息是多媒体上传且已处理；false 表示与本服务无关
     */
    public boolean handleIfMediaUpload(MessageEnvelope envelope) {
        if (envelope.messageId() == null || envelope.messageId() != MULTIMEDIA_UPLOAD) {
            return false;
        }
        Map<String, Object> payload = envelope.payload();
        if (payload == null) {
            return true;
        }

        Long fileId = asLong(payload.get("fileId"));
        if (fileId == null) {
            LOGGER.debug("0x0801 envelope without fileId from {}", envelope.deviceId());
            return true;
        }

        String fileType = asString(payload.get("fileType"), "unknown");
        MediaFile file = new MediaFile(
                null,
                envelope.deviceId(),
                fileId,
                fileType,
                asString(payload.get("fileFormat"), null),
                asString(payload.get("fileName"), null),
                asLong(payload.get("size")),
                asString(payload.get("accessAddress"), null),
                asInteger(payload.get("channelId")),
                asInteger(payload.get("event")),
                envelope.receivedAt() == null ? java.time.Instant.now().toString() : envelope.receivedAt());

        media.insertIgnore(file);
        LOGGER.info("Stored media metadata for {}: fileId={} type={} format={}",
                envelope.deviceId(), fileId, fileType, file.fileFormat());
        return true;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }
}
