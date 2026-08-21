package io.github.jtplatform.boot;

import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.recording.RecordingStorageMetrics;
import io.github.jtplatform.media.recording.RecordingStorageSnapshot;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在 all-in-one 网关的内网 HTTP 基址暴露录像存储状态。
 *
 * <p>媒体节点自身的 78N0 管理端口也有同名端点，但控制台不应拼接实例端口，更不能使用给浏览器的
 * 公网 accessAddress；统一从网关 8100 内网基址读取，部署拓扑变化时控制台无需感知。
 */
@RestController
public final class RecordingMetricsController {
    private final RecordingStorageMetrics metrics;
    private final RecordingProperties properties;

    public RecordingMetricsController(
            RecordingStorageMetrics metrics, RecordingProperties properties) {
        this.metrics = metrics;
        this.properties = properties;
    }

    @GetMapping("/metrics/recording")
    public RecordingMetricsResponse recording() throws IOException {
        RecordingStorageSnapshot snapshot = metrics.snapshot();
        return new RecordingMetricsResponse(
                snapshot.occupiedBytes(), snapshot.usableBytes(), snapshot.totalBytes(),
                properties.getRetentionDays(), properties.getMaxBytes(),
                properties.isRealtimeEnabled(), properties.isPlaybackEnabled());
    }

    public record RecordingMetricsResponse(
            long recordingOccupiedBytes,
            long recordingUsableBytes,
            long recordingTotalBytes,
            int retentionDays,
            long maxBytes,
            boolean realtimeEnabled,
            boolean playbackEnabled) {}
}
