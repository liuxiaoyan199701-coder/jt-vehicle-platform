package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.LiveStatus;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.repository.TimeBounds;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 录像检索代理。平台分片与终端 SD 卡资源具有不同的可用性语义，因此始终分组返回。
 * 在任何网关调用之前先校验车辆数据范围，越权只暴露“车辆不存在”。
 */
@RestController
@RequestMapping("/api/recordings")
public class RecordingProxyController {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingProxyController.class);
    private static final Duration MAX_RANGE = Duration.ofDays(7);
    private static final DateTimeFormatter DEVICE_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final RestClient gateway;
    private final RestClient commandGateway;
    private final VehicleService vehicles;
    private final StatusRepository statuses;

    public RecordingProxyController(
            RestClient gatewayRestClient,
            RestClient commandGatewayRestClient,
            VehicleService vehicles,
            StatusRepository statuses) {
        this.gateway = gatewayRestClient;
        this.commandGateway = commandGatewayRestClient;
        this.vehicles = vehicles;
        this.statuses = statuses;
    }

    @GetMapping("/search")
    @RequirePermission(Permissions.RECORDING_SEARCH)
    public ApiResponse<RecordingSearchResult> search(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "1") int channel,
            @RequestParam(required = false) String streamKind,
            @RequestParam Instant startTime,
            @RequestParam Instant endTime,
            DataScope scope) {
        // MUST 在参数细节校验、任一 HTTP 调用和在线状态判断之前完成：范围外设备无论参数
        // 是否有效都只暴露“不存在”，避免借错误差异枚举跨租户设备。
        String canonicalId = vehicles.requireVisibleDevice(deviceId, scope);
        validateRange(startTime, endTime);
        if (channel < 1 || channel > 255) {
            throw new IllegalArgumentException("通道号必须在 1 到 255 之间");
        }

        String kind = streamKind == null || streamKind.isBlank()
                ? "main"
                : streamKind.trim().toLowerCase(Locale.ROOT);
        if (!kind.equals("main") && !kind.equals("sub")) {
            throw new IllegalArgumentException("码流类型只能是 main 或 sub");
        }

        PlatformSource platform = searchPlatform(canonicalId, channel, kind, startTime, endTime);
        boolean online = statuses.findLiveByDevice(canonicalId, scope)
                .map(LiveStatus::online)
                .orElse(false);
        DeviceSource device = online
                ? searchDevice(canonicalId, channel, kind, startTime, endTime)
                : new DeviceSource(false, "设备离线", List.of());
        return ApiResponse.ok(new RecordingSearchResult(platform, device));
    }

    /** 告警时刻前后五分钟的平台录像。设备侧不参与，避免打开详情就向车机下发指令。 */
    @GetMapping("/around")
    @RequirePermission(Permissions.RECORDING_SEARCH)
    public ApiResponse<List<RecordingRange>> around(
            @RequestParam String deviceId,
            @RequestParam String at,
            @RequestParam(defaultValue = "1") int channel,
            DataScope scope) {
        String canonicalId = vehicles.requireVisibleDevice(deviceId, scope);
        Instant normalizedAt = TimeBounds.instant(at);
        Instant start = normalizedAt.minus(Duration.ofMinutes(5));
        Instant end = normalizedAt.plus(Duration.ofMinutes(5));
        PlatformSource result = searchPlatform(canonicalId, channel, "main", start, end);
        return result.available()
                ? ApiResponse.ok(result.segments())
                : ApiResponse.error("5030", result.reason());
    }

    private PlatformSource searchPlatform(
            String deviceId, int channel, String streamKind, Instant startTime, Instant endTime) {
        try {
            List<RecordingRange> ranges = gateway.get()
                    .uri(uriBuilder -> uriBuilder.path("/recordings/search")
                            .queryParam("deviceId", deviceId)
                            .queryParam("channel", channel)
                            .queryParam("streamKind", streamKind)
                            .queryParam("startTime", startTime.toString())
                            .queryParam("endTime", endTime.toString())
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<RecordingRange>>() {});
            List<RecordingRange> normalized = ranges == null ? List.of() : ranges.stream()
                    .map(range -> new RecordingRange(
                            range.startTime(), range.endTime(),
                            range.channel() == null ? channel : range.channel(),
                            range.streamKind() == null ? streamKind : range.streamKind(),
                            range.source() == null ? "platform" : range.source()))
                    .toList();
            return new PlatformSource(true, null, normalized);
        } catch (ResourceAccessException unreachable) {
            LOGGER.error("Gateway unreachable while searching platform recordings for {}", deviceId, unreachable);
            return new PlatformSource(false, "无法连接到接入网关", List.of());
        } catch (RestClientException failure) {
            LOGGER.error("Platform recording search failed for {}", deviceId, failure);
            return new PlatformSource(false, "平台侧录像检索失败", List.of());
        }
    }

    private DeviceSource searchDevice(
            String deviceId, int channel, String streamKind, Instant startTime, Instant endTime) {
        Map<String, Object> request = Map.of(
                "clientId", deviceId,
                "channelNo", channel,
                "startTime", deviceTime(startTime),
                "endTime", deviceTime(endTime),
                "warnBit1", 0,
                "warnBit2", 0,
                "mediaType", 3,
                "streamType", streamKind.equals("sub") ? 2 : 1,
                "storageType", 0);
        try {
            DeviceResponse response = commandGateway.post()
                    .uri("/device/9205")
                    .body(request)
                    .retrieve()
                    .body(DeviceResponse.class);
            List<DeviceResource> resources = response == null || response.items() == null
                    ? List.of()
                    : response.items().stream().map(RecordingProxyController::normalize).toList();
            return new DeviceSource(true, null, resources);
        } catch (RestClientResponseException rejected) {
            String lower = rejected.getResponseBodyAsString().toLowerCase(Locale.ROOT);
            String reason;
            if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("no response")) {
                reason = "设备未在 10 秒内返回资源列表";
            } else if (lower.contains("unsupported")) {
                reason = "终端不支持录像资源查询指令";
            } else {
                reason = "设备侧资源列表查询失败";
            }
            LOGGER.warn("Device recording search rejected for {}: {}", deviceId, rejected.getMessage());
            return new DeviceSource(false, reason, List.of());
        } catch (ResourceAccessException timeout) {
            LOGGER.warn("Device recording search timed out for {}", deviceId, timeout);
            return new DeviceSource(false, "设备未在 10 秒内返回资源列表", List.of());
        } catch (RestClientException failure) {
            LOGGER.error("Device recording search failed for {}", deviceId, failure);
            return new DeviceSource(false, "设备侧资源列表查询失败", List.of());
        }
    }

    private static void validateRange(Instant startTime, Instant endTime) {
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }
        if (Duration.between(startTime, endTime).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("录像检索时间跨度不能超过 7 天");
        }
    }

    static String deviceTime(Instant instant) {
        return DEVICE_TIME.format(instant.atOffset(Timestamps.ZONE));
    }

    private static DeviceResource normalize(DeviceItem item) {
        return new DeviceResource(
                item.channelNo(),
                Timestamps.ofDeviceLocal(item.startTime()),
                Timestamps.ofDeviceLocal(item.endTime()),
                item.warnBit(), item.mediaType(), item.streamType(), item.storageType(), item.size());
    }

    public record RecordingSearchResult(PlatformSource platform, DeviceSource device) {}

    public record PlatformSource(boolean available, String reason, List<RecordingRange> segments) {}

    public record DeviceSource(boolean available, String reason, List<DeviceResource> resources) {}

    /** 网关平台侧分片；一期网关目前只保证起止字段，其余字段保持向前兼容。 */
    public record RecordingRange(
            Instant startTime, Instant endTime, Integer channel, String streamKind, String source) {}

    private record DeviceResponse(List<DeviceItem> items) {}

    private record DeviceItem(
            int channelNo,
            String startTime,
            String endTime,
            long warnBit,
            int mediaType,
            int streamType,
            int storageType,
            long size) {}

    public record DeviceResource(
            int channel,
            String startTime,
            String endTime,
            long warnBit,
            int mediaType,
            int streamType,
            int storageType,
            long size) {}
}
