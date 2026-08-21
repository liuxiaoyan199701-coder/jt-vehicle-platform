package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.AuditContext;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * 开流代理。
 *
 * <p>前端不直连网关，原因有二：网关的 {@code /stream/open} 没有任何 CORS 配置，浏览器跨域
 * 会被拦；网关的 8100 端口还承载着 46 个无认证的 {@code /device/**} 下行指令接口，不应该
 * 暴露给浏览器。
 *
 * <p>网关返回的 {@code wsUrl} 是指向媒体节点可达地址（已配置为公网 IP）的绝对地址，
 * 播放器会直接连它——WebSocket 不受同源策略限制，这一跳不需要代理。
 */
@RestController
@RequestMapping("/api/stream")
public class StreamProxyController {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamProxyController.class);

    private final RestClient gateway;
    private final ObjectMapper objectMapper;
    private final String publicWebsocketBaseUrl;
    private final VehicleService vehicles;

    public StreamProxyController(
            RestClient gatewayRestClient, ObjectMapper objectMapper,
            ConsoleProperties properties, VehicleService vehicles) {
        this.gateway = gatewayRestClient;
        this.objectMapper = objectMapper;
        this.publicWebsocketBaseUrl = properties.getMedia().getPublicWebsocketBaseUrl();
        this.vehicles = vehicles;
    }

    /**
     * 实时视频开流（主码流/子码流/对讲）。
     */
    @PostMapping("/open")
    @RequirePermission(Permissions.VIDEO_PLAY)
    @Audited(value = "开启实时视频", resourceType = "vehicle")
    public ApiResponse<Map<String, Object>> open(
            @RequestBody Map<String, Object> request, DataScope scope) {
        return doOpen(request, scope);
    }

    /**
     * 录像回放开流。与实时开流同一条网关链路，仅强制 streamKind=playback 并校验回放权限。
     */
    @PostMapping("/open-playback")
    @RequirePermission(Permissions.RECORDING_PLAYBACK)
    @Audited(value = "回放录像", resourceType = "vehicle")
    public ApiResponse<Map<String, Object>> openPlayback(
            @RequestBody Map<String, Object> request, DataScope scope) {
        Map<String, Object> playbackRequest = new LinkedHashMap<>(request);
        playbackRequest.put("streamKind", "playback");
        return doOpen(playbackRequest, scope);
    }

    private ApiResponse<Map<String, Object>> doOpen(
            Map<String, Object> request, DataScope scope) {
        Object deviceId = request.get("deviceId");
        if (deviceId == null || deviceId.toString().isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        // 归属校验必须在触达网关之前：越权请求 MUST NOT 产生任何开流信令。
        String canonicalId = vehicles.requireVisibleDevice(deviceId.toString(), scope);
        AuditContext.resource("vehicle", canonicalId);
        Map<String, Object> gatewayRequest = new LinkedHashMap<>(request);
        gatewayRequest.put("deviceId", canonicalId);
        gatewayRequest.putIfAbsent("channel", 1);
        gatewayRequest.putIfAbsent("streamKind", "main");

        try {
            Map<String, Object> ticket = gateway.post()
                    .uri("/stream/open")
                    .body(gatewayRequest)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            rewriteWebsocketUrl(ticket);
            LOGGER.info("Opened stream for {} channel {}: {}",
                    canonicalId,
                    gatewayRequest.get("channel"),
                    ticket == null ? "null" : ticket.get("state"));
            return ApiResponse.ok(ticket);
        } catch (RestClientResponseException failure) {
            GatewayError error = parseGatewayError(failure);
            LOGGER.warn("Stream open rejected for {} ({} {}): {}",
                    canonicalId, failure.getStatusCode().value(), error.code(), error.message());
            return ApiResponse.error("5030", friendlyMessage(error));
        } catch (ResourceAccessException unreachable) {
            LOGGER.error("Gateway unreachable while opening stream for {}", canonicalId, unreachable);
            return ApiResponse.error("5030", "无法连接到接入网关，请检查 jt-platform 服务是否运行");
        } catch (RestClientException failure) {
            LOGGER.error("Unexpected stream open failure for {}", canonicalId, failure);
            return ApiResponse.error("5030", "开流失败：" + failure.getMessage());
        }
    }

    /**
     * 把网关返回的 {@code ws://<媒体节点>:7815/ws?...} 改写成经 nginx 转发的 wss 地址。
     *
     * <p>HTTPS 页面下浏览器会按混合内容策略拦掉明文 {@code ws://}，不改写视频就打不开。
     * 只替换协议、主机和路径，查询串（含一次性 token）原样保留。
     */
    private void rewriteWebsocketUrl(Map<String, Object> ticket) {
        if (ticket == null || publicWebsocketBaseUrl == null || publicWebsocketBaseUrl.isBlank()) {
            return;
        }
        Object original = ticket.get("wsUrl");
        if (original == null) {
            return;
        }
        String url = original.toString();
        int queryStart = url.indexOf('?');
        String query = queryStart >= 0 ? url.substring(queryStart) : "";
        String rewritten = publicWebsocketBaseUrl + query;
        ticket.put("wsUrl", rewritten);
        ticket.remove("originalWsUrl");
        LOGGER.debug("Rewrote media websocket endpoint for public access");
    }

    /**
     * 网关的业务错误体是 {@code {code, message, timestamp}}。
     * 拿不到或解析不了时退回一个仅含状态码的占位，不让解析失败盖掉真正的错误。
     */
    private GatewayError parseGatewayError(RestClientResponseException failure) {
        String body = failure.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return new GatewayError("", "HTTP " + failure.getStatusCode().value());
        }
        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(body, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return new GatewayError(
                    String.valueOf(parsed.getOrDefault("code", "")),
                    String.valueOf(parsed.getOrDefault("message", body)));
        } catch (RuntimeException unparsable) {
            return new GatewayError("", body);
        }
    }

    /**
     * 把网关的错误翻译成用户能据此行动的中文提示。
     *
     * <p>网关把所有 {@code StreamCommandException} 都映射成 502 + SIGNAL_COMMAND_FAILED
     * （见 jt-api 的 StreamApiExceptionHandler），设备离线、终端不响应、信令进程故障挤在同一个
     * 码里，只能靠 message 内容进一步区分。直接把 502 和原始 JSON 抛给用户没有任何指导意义。
     */
    private static String friendlyMessage(GatewayError error) {
        String message = error.message() == null ? "" : error.message();
        String lower = message.toLowerCase();

        if (lower.contains("offline") || lower.contains("no session")) {
            return "设备当前不在线，无法开流。请确认车机已连接平台（可在实时监控页查看在线状态）。";
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("no response")) {
            return "设备已连接但未响应开流指令，可能是终端不支持 JT/T 1078 或网络不稳定。";
        }

        return switch (error.code()) {
            case "NO_MEDIA_CAPACITY" ->
                    "媒体节点已达容量上限，无法分配新的视频流。可调整 jt.media.capacity.max-streams。";
            case "AUTHENTICATION_FAILED" -> "开流鉴权失败，请检查网关的 JWT 配置。";
            case "INVALID_REQUEST" -> "开流参数不合法：" + message;
            case "SIGNAL_COMMAND_FAILED" -> "网关下发开流指令失败：" + message;
            default -> message.isBlank() ? "开流失败" : "开流失败：" + message;
        };
    }

    private record GatewayError(String code, String message) {}
}
