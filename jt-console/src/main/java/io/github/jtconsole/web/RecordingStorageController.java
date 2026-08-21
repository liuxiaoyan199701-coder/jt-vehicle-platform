package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 录像磁盘状态只读代理。只允许通过 gatewayRestClient 的内网基址访问。 */
@RestController
@RequestMapping("/api/system/recording-storage")
public class RecordingStorageController {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingStorageController.class);

    private final RestClient gateway;

    public RecordingStorageController(RestClient gatewayRestClient) {
        this.gateway = gatewayRestClient;
    }

    @GetMapping
    @RequirePermission(Permissions.SYSTEM_CONFIG_VIEW)
    public ApiResponse<Map<String, Object>> get() {
        try {
            Map<String, Object> metrics = gateway.get()
                    // 一律用相对路径，RestClient 会解析到内网 base-url；禁止接收或拼接公网地址。
                    .uri("/metrics/recording")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return ApiResponse.ok(metrics == null ? Map.of() : metrics);
        } catch (RestClientException failure) {
            LOGGER.error("Failed to query recording storage metrics from internal gateway", failure);
            return ApiResponse.error("5030", "录像存储状态暂不可用");
        }
    }
}
