package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.AuditContext;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.UpgradePackage;
import io.github.jtconsole.operations.UpgradeService;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.DeviceAttributeRepository;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.PathVariable;
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
 * 下行指令代理：把浏览器里的「远程控制面板」接到网关的 {@code /device/**} 指令端点。
 *
 * <p>为什么需要代理而不是让前端直连网关：
 * <ul>
 *   <li>网关的 52 个 {@code /device/**} 端点<b>没有任何认证</b>，且覆盖了锁车、
 *       升级、参数篡改等高危能力。直连等于给任何打开页面的人发根指挥棒</li>
 *   <li>生产部署中 8100 只绑 127.0.0.1，浏览器本来就够不着</li>
 *   <li>本层执行白名单 + 参数校验 + 错误翻译，前端只会接触到有意的能力子集</li>
 * </ul>
 *
 * <p>错误语义沿用 {@code StreamProxyController} 的约定：网关把设备离线、应答超时都映射成
 * HTTP 502 + {@code SIGNAL_COMMAND_FAILED}，只能靠 message 内容区分；本层翻译成
 * 用户能据此行动的中文提示。
 */
@RestController
@RequestMapping("/api/commands")
public class CommandProxyController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandProxyController.class);

    private final RestClient gateway;
    private final ObjectMapper objectMapper;
    private final DeviceAttributeRepository deviceAttributes;
    private final VehicleService vehicles;
    private final UpgradeService upgrades;

    public CommandProxyController(
            RestClient commandGatewayRestClient,
            ObjectMapper objectMapper,
            DeviceAttributeRepository deviceAttributes,
            VehicleService vehicles,
            UpgradeService upgrades) {
        this.gateway = commandGatewayRestClient;
        this.objectMapper = objectMapper;
        this.deviceAttributes = deviceAttributes;
        this.vehicles = vehicles;
        this.upgrades = upgrades;
    }

    /**
     * 下发一条白名单指令。
     *
     * @param command 白名单键：text / ptz / ptz-adjust / vehicle-control /
     *                photo / callback / track-follow
     * @param body    各指令的请求体，字段见各 builder 的 javadoc
     */
    @PostMapping("/{command}")
    @RequirePermission(Permissions.COMMAND_SEND)
    @Audited(value = "下发终端指令", resourceType = "vehicle")
    public ApiResponse<Map<String, Object>> send(
            @PathVariable String command, @RequestBody Map<String, Object> body,
            DataScope scope) {
        // 归属校验必须在构造网关请求之前：越权指令 MUST NOT 触达网关。
        String targetDeviceId = vehicles.requireVisibleDevice(
                body == null ? null : String.valueOf(body.get("deviceId")), scope);
        AuditContext.resource("vehicle", targetDeviceId);
        AuditContext.detail("指令=" + command);
        Prepared prepared = switch (command) {
            case "text" -> textCommand(body);
            case "ptz" -> ptzCommand(body);
            case "ptz-adjust" -> ptzAdjustCommand(body);
            case "vehicle-control" -> vehicleControlCommand(body);
            case "photo" -> photoCommand(body);
            case "callback" -> callbackCommand(body);
            case "track-follow" -> trackFollowCommand(body);
            case "query-params" -> new Prepared("/device/8104", base(requireDeviceId(body)));
            case "query-attributes" -> new Prepared("/device/8107", base(requireDeviceId(body)));
            case "upgrade" -> upgradeCommand(body);
            default -> throw new IllegalArgumentException("未知指令：" + command);
        };

        try {
            Map<String, Object> reply = gateway.post()
                    .uri(prepared.path())
                    .body(prepared.request())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            LOGGER.info("Command {} to {} accepted, response {}",
                    command, prepared.request().get("clientId"), describeReply(reply));
            // 查询类指令直接透传终端上报的数据（参数表/属性），不做「已确认」翻译。
            if (command.equals("query-params") || command.equals("query-attributes")) {
                return ApiResponse.ok(reply == null ? Map.of() : reply);
            }
            return ApiResponse.ok(result(reply, command));
        } catch (RestClientResponseException failure) {
            GatewayError error = parseGatewayError(failure);
            LOGGER.warn("Command {} rejected for {} ({} {}): {}",
                    command, prepared.request().get("clientId"),
                    failure.getStatusCode().value(), error.code(), error.message());
            return ApiResponse.error("5030", friendlyMessage(error));
        } catch (ResourceAccessException unreachable) {
            LOGGER.error("Gateway unreachable while sending command {}", command, unreachable);
            return ApiResponse.error("5030", "无法连接到接入网关，请检查 jt-platform 服务是否运行");
        } catch (RestClientException failure) {
            LOGGER.error("Unexpected command failure {}", command, failure);
            return ApiResponse.error("5030", "指令下发失败：" + failure.getMessage());
        }
    }

    // ---------------- 白名单指令构造 ----------------

    /**
     * 文本下发 0x8300：{@code {deviceId, content, display, tts, emergency}}
     * emergency 紧急（sign bit0），display 在终端显示器上显示（bit2），tts 语音播读（bit3）。
     */
    private Prepared textCommand(Map<String, Object> body) {
        String deviceId = requireDeviceId(body);
        String content = requireText(body, "content", 200);
        boolean display = bool(body, "display", true);
        boolean tts = bool(body, "tts", false);
        boolean emergency = bool(body, "emergency", false);
        int sign = (emergency ? 0b0001 : 0) | (display ? 0b0100 : 0) | (tts ? 0b1000 : 0);

        Map<String, Object> request = base(deviceId);
        request.put("sign", sign);
        request.put("type", 1);          // 2019 版才编码，2011/2013 自动忽略
        request.put("content", content);
        return new Prepared("/device/8300", request);
    }

    /**
     * 云台转动 0x9301：{@code {deviceId, channelNo, param1, param2}}
     * param1：0 停止 / 1 上 / 2 下 / 3 左 / 4 右；param2 速度 0~255。
     */
    private Prepared ptzCommand(Map<String, Object> body) {
        String deviceId = requireDeviceId(body);
        int channelNo = intInRange(body, "channelNo", 1, 1, 255);
        int param1 = intInRange(body, "param1", 0, 0, 4);
        int param2 = intInRange(body, "param2", 128, 0, 255);

        Map<String, Object> request = base(deviceId);
        request.put("channelNo", channelNo);
        request.put("param1", param1);
        request.put("param2", param2);
        return new Prepared("/device/9301", request);
    }

    /**
     * 云台调整 0x9302-9306：{@code {deviceId, channelNo, action, param}}
     * action：focus 调焦 / iris 光圈 / wiper 雨刷 / fill-light 补光 / zoom 变倍；
     * param：focus/iris/zoom 为 0 调大 1 调小；wiper/fill-light 为 0 停止 1 启动。
     */
    private Prepared ptzAdjustCommand(Map<String, Object> body) {
        String deviceId = requireDeviceId(body);
        int channelNo = intInRange(body, "channelNo", 1, 1, 255);
        String action = requireText(body, "action", 16).toLowerCase();
        int param = intInRange(body, "param", 0, 0, 1);

        String messageId = switch (action) {
            case "focus" -> "9302";
            case "iris" -> "9303";
            case "wiper" -> "9304";
            case "fill-light" -> "9305";
            case "zoom" -> "9306";
            default -> throw new IllegalArgumentException("未知云台动作：" + action);
        };

        Map<String, Object> request = base(deviceId);
        request.put("channelNo", channelNo);
        request.put("param", param);
        return new Prepared("/device/" + messageId, request);
    }

    /**
     * 车辆控制 0x8500：{@code {deviceId, lock}}，lock=true 加锁、false 解锁。
     *
     * <p>0x8500 是双版本报文：2011/2013 版在 1 字节 type 的 bit0 表达加/解锁；
     * 2019 版 type 是 2 字节控制类型（1=车门加锁），由 param 字节表达 0 解锁 1 加锁。
     * 编码版本由设备注册版本决定、代理层无法强制，因此按设备实际版本构造请求体。
     * 版本未知（设备从未上报过）时按 2019 编码——存量 2019 设备占绝大多数。
     */
    private Prepared vehicleControlCommand(Map<String, Object> body) {
        String deviceId = requireDeviceId(body);
        boolean lock = bool(body, "lock", false);

        String version = deviceAttributes.findProtocolVersion(deviceId).orElse("");
        boolean is2019 = version.contains("2019");

        Map<String, Object> request = base(deviceId);
        if (is2019) {
            request.put("type", 1);              // 1 = 车门加锁
            request.put("param", lock ? 1 : 0);  // 0 解锁 / 1 加锁
        } else {
            request.put("type", lock ? 1 : 0);   // bit0：0 解锁 / 1 加锁
            request.put("param", 0);
        }
        return new Prepared("/device/8500", request);
    }

    /**
     * 摄像头立即拍摄 0x8801：{@code {deviceId, channelId, count, resolution}}
     * count 为拍摄张数（1~10）；resolution 为分辨率枚举（默认 2 = 640×480）。
     * 照片随后经 0x0801 上传，可在「拍照结果」里看到。
     */
    private Prepared photoCommand(Map<String, Object> body) {
        String deviceId = requireDeviceId(body);
        int channelId = intInRange(body, "channelId", 1, 1, 255);
        int count = intInRange(body, "count", 1, 1, 10);
        int resolution = intInRange(body, "resolution", 2, 1, 8);

        Map<String, Object> request = base(deviceId);
        request.put("channelId", channelId);
        request.put("command", count);           // 非 0/65535 即拍照张数
        request.put("time", 0);                  // 0 = 最小间隔
        request.put("save", 0);                  // 实时上传
        request.put("resolution", resolution);
        request.put("quality", 5);
        request.put("brightness", 128);
        request.put("contrast", 64);
        request.put("saturation", 64);
        request.put("chroma", 128);
        return new Prepared("/device/8801", request);
    }

    /**
     * 电话回拨 0x8400：{@code {deviceId, phoneNumber, mode}}
     * mode：call 通话 / monitor 监听。
     */
    private Prepared callbackCommand(Map<String, Object> body) {
        String deviceId = requireDeviceId(body);
        String phone = requireText(body, "phoneNumber", 20);
        if (!phone.matches("[0-9]{5,20}")) {
            throw new IllegalArgumentException("电话号码必须是 5-20 位数字");
        }
        String mode = requireText(body, "mode", 8).toLowerCase();
        int type = switch (mode) {
            case "call", "" -> 0;
            case "monitor" -> 1;
            default -> throw new IllegalArgumentException("未知回拨模式：" + mode);
        };

        Map<String, Object> request = base(deviceId);
        request.put("type", type);
        request.put("phoneNumber", phone);
        return new Prepared("/device/8400", request);
    }

    /**
     * 临时位置跟踪 0x8202：{@code {deviceId, interval, validity}}
     * interval 上报间隔秒（1~3600），validity 有效期秒（1~86400）。
     */
    private Prepared trackFollowCommand(Map<String, Object> body) {
        String deviceId = requireDeviceId(body);
        int interval = intInRange(body, "interval", 5, 1, 3600);
        int validity = intInRange(body, "validity", 300, 1, 86400);

        Map<String, Object> request = base(deviceId);
        request.put("interval", interval);
        request.put("validityPeriod", validity);
        return new Prepared("/device/8202", request);
    }

    /**
     * 下发升级包 0x8108：{@code {deviceId, packageId}}。
     * 包体在服务端按 packageId 读取，前端不直传二进制；packet 以 base64 编码进 8108。
     */
    private Prepared upgradeCommand(Map<String, Object> body) {
        String deviceId = requireDeviceId(body);
        Object packageIdValue = body.get("packageId");
        if (!(packageIdValue instanceof Number number)) {
            throw new IllegalArgumentException("packageId 必须是数字");
        }
        long packageId = number.longValue();
        UpgradePackage pkg = upgrades.find(packageId)
                .orElseThrow(() -> new IllegalArgumentException("升级包不存在"));
        byte[] bytes = upgrades.loadBytes(packageId);

        Map<String, Object> request = base(deviceId);
        request.put("type", 0);  // 升级类型：0 = 终端
        request.put("makerId", pkg.makerId());
        request.put("version", pkg.version());
        request.put("packet", Base64.getEncoder().encodeToString(bytes));
        return new Prepared("/device/8108", request);
    }

    // ---------------- 响应加工 ----------------

    /**
     * 各指令的应答结构不同：大部分是 T0001（resultCode），车辆控制是带位置的
     * T0201_0500，拍照是 T0805（result + 照片 ID 列表）。统一成 {message, extra}。
     */
    private Map<String, Object> result(Map<String, Object> reply, String command) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object resultCode = reply.get("resultCode");
        if (resultCode instanceof Number code && command != null) {
            int value = code.intValue();
            result.put("message", switch (value) {
                case 0 -> "终端已确认";
                case 1 -> "终端执行失败";
                case 2 -> "消息有误";
                case 3 -> "终端不支持该指令";
                case 4 -> "报警处理确认";
                default -> "终端返回未知结果码 " + value;
            });
            result.put("success", value == 0);
            result.put("resultCode", value);
            return result;
        }

        Object photoResult = reply.get("result");
        if (photoResult instanceof Number code) {
            int value = code.intValue();
            result.put("message", switch (value) {
                case 0 -> "拍摄成功";
                case 1 -> "拍摄失败";
                case 2 -> "通道不支持";
                default -> "拍摄返回未知结果码 " + value;
            });
            result.put("success", value == 0);
            result.put("result", value);
            result.put("photoIds", reply.get("id"));
            return result;
        }

        // T0201_0500 车辆控制应答没有结果码，能收到即代表执行
        result.put("message", "终端已应答");
        result.put("success", true);
        return result;
    }

    private static String describeReply(Map<String, Object> reply) {
        if (reply == null) {
            return "empty";
        }
        if (reply.get("resultCode") != null) {
            return "resultCode=" + reply.get("resultCode");
        }
        if (reply.get("result") != null) {
            return "result=" + reply.get("result");
        }
        return "ok";
    }

    // ---------------- 通用辅助 ----------------

    private static Map<String, Object> base(String deviceId) {
        Map<String, Object> request = new LinkedHashMap<>();
        // 网关 findSession 对 deviceId 走 O(1) 哈希命中，对手机号走线性扫描，
        // 因此始终传 canonical deviceId 最稳。
        request.put("clientId", deviceId);
        return request;
    }

    private static String requireDeviceId(Map<String, Object> body) {
        Object deviceId = body.get("deviceId");
        if (deviceId == null || deviceId.toString().isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        return deviceId.toString().trim();
    }

    private static String requireText(Map<String, Object> body, String key, int maxLength) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(key + " 不能为空");
        }
        String text = value.toString().trim();
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(key + " 过长（最多 " + maxLength + " 字符）");
        }
        return text;
    }

    private static int intInRange(Map<String, Object> body, String key, int defaultValue,
                                  int min, int max) {
        Object value = body.get(key);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(key + " 必须是整数");
            }
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(key + " 超出范围 [" + min + ", " + max + "]");
        }
        return parsed;
    }

    private static boolean bool(Map<String, Object> body, String key, boolean defaultValue) {
        Object value = body.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private GatewayError parseGatewayError(RestClientResponseException failure) {
        String body = failure.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return new GatewayError("", "HTTP " + failure.getStatusCode().value());
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    body, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return new GatewayError(
                    String.valueOf(parsed.getOrDefault("code", "")),
                    String.valueOf(parsed.getOrDefault("message", body)));
        } catch (RuntimeException unparsable) {
            return new GatewayError("", body);
        }
    }

    /**
     * 网关把设备离线、应答超时统一映射成 502 + SIGNAL_COMMAND_FAILED，
     * 只能靠 message 内容区分，这里翻译成用户能据此行动的中文。
     */
    private static String friendlyMessage(GatewayError error) {
        String message = error.message() == null ? "" : error.message();
        String lower = message.toLowerCase();

        if (lower.contains("offline") || lower.contains("no session")) {
            return "设备当前不在线，无法下发指令。请确认车机已连接平台。";
        }
        if (lower.contains("unsupported")) {
            return "终端不支持该指令（设备已返回「不支持」应答）。";
        }
        if (lower.contains("failed to execute")) {
            return "终端执行失败（设备已返回「失败」应答）。";
        }
        if (lower.contains("as invalid")) {
            return "终端认为消息有误，请检查指令参数。";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "指令已下发但设备在 10 秒内没有应答，请检查终端是否支持该指令。";
        }
        if (lower.contains("请勿重复发送") || lower.contains("waiting")) {
            return "上一条指令还在等待应答，请稍后再试。";
        }
        return switch (error.code()) {
            case "INVALID_REQUEST" -> "指令参数不合法：" + message;
            case "SIGNAL_COMMAND_FAILED" -> "网关下发指令失败：" + message;
            default -> message.isBlank() ? "指令下发失败" : "指令下发失败：" + message;
        };
    }

    private record Prepared(String path, Map<String, Object> request) {}

    private record GatewayError(String code, String message) {}
}
