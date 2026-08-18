package io.github.jtconsole.ingest;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.geo.CoordTransform;
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
        Capture capture = Capture.from(payload.get("location"));
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
                capture.lat(),
                capture.lng(),
                capture.gcjLat(),
                capture.gcjLng(),
                // 必须归一化到东八区，与其它投递路径（见 LocationSample.parse）一致。
                // 网关送来的是 Instant.toString()，即带 Z 后缀的 UTC、纳秒精度。原样入库的后果
                // 不只是界面差 8 小时：时间列是字符串，TimeBounds 把查询边界归一化成 +08:00 后，
                // 字典序比较对不上，多媒体页按时间段筛会**静默查不到**，告警时段联查更是永远为空。
                Timestamps.normalize(
                        envelope.receivedAt() == null ? Timestamps.now() : envelope.receivedAt()));

        media.insertIgnore(file);
        LOGGER.info("Stored media metadata for {}: fileId={} type={} format={} located={}",
                envelope.deviceId(), fileId, fileType, file.fileFormat(), file.locatable());
        return true;
    }

    /**
     * 抓拍位置。
     *
     * <p>网关在 0x0801 的信封里塞了一个 {@code location} 子对象（见
     * {@code ProtocolPayloadMapper.enrichMultimedia}），字段名与 0x0200 位置汇报一致。
     * 它一直在传，只是以前被丢掉了。
     *
     * <p>判定与写入口径**刻意与 {@link LocationSample} 保持一致**：坐标不可用时四个值全为
     * {@code null}，绝不写 0。两处若不一致，同一台车的轨迹点和抓拍点会落在地图上不同的地方。
     */
    private record Capture(Double lat, Double lng, Double gcjLat, Double gcjLng) {

        private static final Capture UNKNOWN = new Capture(null, null, null, null);

        static Capture from(Object raw) {
            if (!(raw instanceof Map<?, ?> location)) {
                return UNKNOWN;
            }
            Double lat = asDouble(location.get("latitude"));
            Double lng = asDouble(location.get("longitude"));
            if (lat == null || lng == null || isInvalid(lat, lng)) {
                return UNKNOWN;
            }
            double[] gcj = CoordTransform.wgs84ToGcj02(lat, lng);
            return new Capture(lat, lng, gcj[0], gcj[1]);
        }

        /** 与 {@code LocationSample.isInvalid} 同一套阈值：(0,0) 与超界值都当作没定位。 */
        private static boolean isInvalid(double lat, double lng) {
            return (Math.abs(lat) < 0.000001D && Math.abs(lng) < 0.000001D)
                    || Math.abs(lat) > 90.0D
                    || Math.abs(lng) > 180.0D;
        }

        private static Double asDouble(Object value) {
            return value instanceof Number number ? number.doubleValue() : null;
        }
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
