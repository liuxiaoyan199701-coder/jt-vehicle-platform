package io.github.jtconsole.ai.vision;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.MediaFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 识别平台抓拍的照片。
 *
 * <p><b>刻意不做成一个独立的 AI 工具</b>。界面上不能出现「第二个模型」，而一个名叫
 * 「识别照片」的工具气泡恰恰会把双模型结构暴露给用户，还会让模型多花一轮去调它、经常忘了调。
 * 描述直接折进 {@code query_photos} 的返回值里——模型查抓拍时「天生就知道」图上是什么。
 *
 * <p>字节从网关取。控制台库里只有元数据，二进制在
 * {@code /var/lib/jt-platform/data/signal/multimedia}，由网关的
 * {@code /files/multimedia/**} 端点提供。
 */
@Service
public class SnapshotVisionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotVisionService.class);

    private final RestClient gateway;
    private final Optional<VisionService> vision;
    private final ConsoleProperties.Ai.Vision config;

    public SnapshotVisionService(
            RestClient gatewayRestClient,
            Optional<VisionService> vision,
            ConsoleProperties properties) {
        this.gateway = gatewayRestClient;
        this.vision = vision;
        this.config = properties.getAi().getVision();
    }

    public boolean available() {
        return vision.isPresent();
    }

    /**
     * 识别一批抓拍，返回 {@code fileId -> 描述} 的映射。
     *
     * <p>只识别前 {@code maxImages} 张：图片按面积折算 token，一次十几张能压过整轮对话的预算，
     * 而回答「最近拍到了什么」并不需要每一张都看。未被识别的照片不会出现在返回值里，
     * 调用方据此在结果中如实说明。
     *
     * <p>永不抛异常。识别不可用时返回空映射，让抓拍查询退化成「只有元数据」——
     * 那仍然是个有用的答案，而不是整个工具调用失败。
     */
    /**
     * 一次识别的结果。
     *
     * @param text            描述正文，覆盖 {@link #coveredTimes} 列出的那几张
     * @param coveredTimes    实际送检的照片拍摄时间，按送检顺序
     */
    public record Described(String text, List<String> coveredTimes) {
        public static final Described NONE = new Described("", List.of());

        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }

    public Described describe(List<MediaFile> photos) {
        return describe(photos, null);
    }

    /**
     * 带观察指令的识别。
     *
     * @param instruction 额外的观察要求，如「重点判断画面是否异常」。为 null 时按通用描述处理
     */
    public Described describe(List<MediaFile> photos, String instruction) {
        if (photos == null || photos.isEmpty() || vision.isEmpty()) {
            return Described.NONE;
        }
        int limit = Math.max(1, config.getMaxImages());
        List<MediaFile> targets = new ArrayList<>();
        List<VisionImage> images = new ArrayList<>();
        for (MediaFile photo : photos) {
            if (images.size() >= limit) {
                break;
            }
            // 只看图片：0x0801 也可能是音频或视频片段，送去图像模型只会白花一次调用。
            if (!isImage(photo)) {
                continue;
            }
            fetch(photo).ifPresent(bytes -> {
                try {
                    images.add(ImagePreparer.prepare(
                            bytes, config.getMaxEdgePixels(), label(photo)));
                    targets.add(photo);
                } catch (RuntimeException undecodable) {
                    LOGGER.debug("抓拍 {} 无法解码：{}", photo.fileId(), undecodable.getMessage());
                }
            });
        }
        if (images.isEmpty()) {
            return Described.NONE;
        }

        try {
            String combined = vision.get().describe(images, instruction);
            // 描述**不按张拆分**：模型按「第 N 张」分段作答，但措辞每次都可能不同，
            // 按标号强行切开会在它换一种写法时把描述割错位——把甲车的画面安到乙车头上，
            // 比不切分严重得多。整段一起给出，同时如实列出覆盖了哪几张。
            return new Described(combined, targets.stream().map(MediaFile::capturedAt).toList());
        } catch (RuntimeException failure) {
            LOGGER.warn("抓拍识别失败：{}", failure.getMessage());
            return Described.NONE;
        }
    }

    private static boolean isImage(MediaFile photo) {
        String type = photo.fileType() == null ? "" : photo.fileType().toLowerCase(java.util.Locale.ROOT);
        String format = photo.fileFormat() == null ? "" : photo.fileFormat().toLowerCase(java.util.Locale.ROOT);
        return type.contains("image") || type.contains("photo") || type.contains("jpg")
                || format.contains("jpg") || format.contains("jpeg") || format.contains("png");
    }

    private static String label(MediaFile photo) {
        StringBuilder text = new StringBuilder(photo.deviceId());
        if (photo.channelId() != null) {
            text.append(" 通道").append(photo.channelId());
        }
        text.append(' ').append(photo.capturedAt());
        if (photo.alarmTriggered()) {
            text.append("（报警触发）");
        }
        return text.toString();
    }

    /**
     * 从网关下载照片字节。
     *
     * <p><b>一律只取路径，走网关内网基址，绝不使用 {@code accessAddress} 里的主机名。</b>
     * 那个地址是给**浏览器**用的：部署方通过 {@code multimedia-access-base-url} 把它配成公网
     * HTTPS 入口，而测试环境的证书是自签名的——控制台照着它去取图会在 TLS 握手就被 JVM 拒掉，
     * 表现为「照片查得到、画面永远读不出来」。即便证书合法，让服务端绕一圈公网访问自己也是
     * 白白多经一层 nginx，在分进程部署下还可能根本不可达。
     */
    private Optional<byte[]> fetch(MediaFile photo) {
        String path = internalPath(photo.accessAddress());
        if (path == null) {
            return Optional.empty();
        }
        try {
            byte[] bytes = gateway.get().uri(path).retrieve().body(byte[].class);
            return Optional.ofNullable(bytes).filter(data -> data.length > 0);
        } catch (RestClientException | IllegalArgumentException failure) {
            // 用 warn 而不是 debug：它会让用户看到「照片读不出来」，是需要运维知道的降级，
            // 不是可以埋掉的细节。这条日志正是这个缺陷上线后唯一能自证的线索。
            LOGGER.warn("抓拍 {} 下载失败（{}）：{}", photo.fileId(), path, failure.getMessage());
            return Optional.empty();
        }
    }

    /** 从可能是绝对地址的 accessAddress 中取出网关内部路径。 */
    private static String internalPath(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            return uri.getRawQuery() == null ? path : path + '?' + uri.getRawQuery();
        } catch (IllegalArgumentException malformed) {
            LOGGER.debug("无法解析访问地址：{}", trimmed);
            return null;
        }
    }
}
