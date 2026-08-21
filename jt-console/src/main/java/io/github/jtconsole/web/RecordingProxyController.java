package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.time.Instant;
import java.util.List;
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

/**
 * 录像检索代理。前端不直连网关的 {@code /recordings/search}，原因与开流/指令代理一致：
 * 网关 8100 端口还承载着无认证的 {@code /device/**} 指令面，不能暴露给浏览器。
 *
 * <p>本层在触达网关前做车辆可见性校验：范围外设备一律以「不存在」拒绝，绝不把越权查询
 * 转发到网关与媒体节点。
 */
@RestController
@RequestMapping("/api/recordings")
public class RecordingProxyController {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingProxyController.class);

    private final RestClient gateway;
    private final VehicleService vehicles;

    public RecordingProxyController(RestClient gatewayRestClient, VehicleService vehicles) {
        this.gateway = gatewayRestClient;
        this.vehicles = vehicles;
    }

    @GetMapping("/search")
    @RequirePermission(Permissions.RECORDING_SEARCH)
    public ApiResponse<List<RecordingRange>> search(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "1") int channel,
            @RequestParam(required = false) String streamKind,
            @RequestParam Instant startTime,
            @RequestParam Instant endTime,
            DataScope scope) {
        String canonicalId = vehicles.requireVisibleDevice(deviceId, scope);

        try {
            List<RecordingRange> ranges = gateway.get()
                    .uri(uriBuilder -> uriBuilder.path("/recordings/search")
                            .queryParam("deviceId", canonicalId)
                            .queryParam("channel", channel)
                            .queryParam("streamKind", streamKind == null || streamKind.isBlank() ? "main" : streamKind)
                            .queryParam("startTime", startTime.toString())
                            .queryParam("endTime", endTime.toString())
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<RecordingRange>>() {});
            return ApiResponse.ok(ranges == null ? List.of() : ranges);
        } catch (ResourceAccessException unreachable) {
            LOGGER.error("Gateway unreachable while searching recordings for {}", canonicalId, unreachable);
            return ApiResponse.error("5030", "无法连接到接入网关，请检查 jt-platform 服务是否运行");
        } catch (RestClientException failure) {
            LOGGER.error("Recording search failed for {}", canonicalId, failure);
            return ApiResponse.error("5030", "录像检索失败：" + failure.getMessage());
        }
    }

    /** 网关返回的录像时间区间。startTime/endTime 为 ISO-8601 时间。 */
    public record RecordingRange(Instant startTime, Instant endTime) {
    }
}
