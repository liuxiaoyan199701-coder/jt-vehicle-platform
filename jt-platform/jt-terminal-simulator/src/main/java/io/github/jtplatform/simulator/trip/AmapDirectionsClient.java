package io.github.jtplatform.simulator.trip;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 高德驾车路径规划客户端。
 *
 * <p>只用 JDK 自带的 HTTP 客户端与项目已在用的 JSON 库，不引入任何新依赖——模拟器的 pom 里有
 * 强制规则禁止把服务端框架带进来，这里也没有理由为一次 GET 请求破例。
 */
public final class AmapDirectionsClient implements DirectionsService {

    private static final String DEFAULT_ENDPOINT = "https://restapi.amap.com/v3/direction/driving";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String endpoint;
    private final Duration requestTimeout;
    private final HttpClient http;

    public AmapDirectionsClient() {
        this(DEFAULT_ENDPOINT);
    }

    /** 接口地址可注入，测试用本地假服务器回放响应，不必联网。 */
    public AmapDirectionsClient(String endpoint) {
        this(endpoint, REQUEST_TIMEOUT);
    }

    AmapDirectionsClient(String endpoint, Duration requestTimeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * 请求一条驾车路线，返回**加密坐标系**的折线点。
     *
     * @throws AmapException 网络失败、响应不是 JSON、接口报错、或返回了空路线
     */
    @Override
    public List<GeoPoint> drivingRoute(GeoPoint origin, GeoPoint destination, String key)
            throws AmapException {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        if (key == null || key.isBlank()) {
            throw new AmapException("未配置地图密钥");
        }

        URI uri = URI.create("%s?origin=%s&destination=%s&extensions=all&output=json&key=%s"
                .formatted(endpoint, coordinate(origin), coordinate(destination), encode(key)));
        HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException timeout) {
            // 必须排在 IOException 之前——它是 IOException 的子类，顺序反了就永远走不到这一支。
            throw new AmapException(
                    "地图服务响应超时（超过 %d 秒）".formatted(requestTimeout.toSeconds()), timeout);
        } catch (IOException failure) {
            throw new AmapException("无法连接地图服务：" + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            // 恢复中断标志，否则调用线程后续的阻塞点会毫无察觉地继续等待。
            Thread.currentThread().interrupt();
            throw new AmapException("路径规划被中断", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new AmapException("地图服务返回 HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    private static List<GeoPoint> parse(String body) throws AmapException {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException malformed) {
            // 代理或门户返回 HTML 登录页时就会走到这里，原样回显开头一段比「解析失败」有用得多。
            throw new AmapException("地图服务返回的不是 JSON：" + preview(body), malformed);
        }

        // status 在 v3 接口里是**字符串** "1"/"0"，不是数字——按数字取会永远得到 0。
        String status = root.path("status").asString("");
        if (!"1".equals(status)) {
            throw new AmapException(describeFailure(
                    root.path("info").asString(""), root.path("infocode").asString("")));
        }

        JsonNode paths = root.path("route").path("paths");
        if (!paths.isArray() || paths.isEmpty()) {
            throw new AmapException("地图服务未返回任何路线，请确认起终点均在国内且可驾车通行");
        }
        List<String> polylines = new ArrayList<>();
        for (JsonNode step : paths.get(0).path("steps")) {
            String polyline = step.path("polyline").asString("");
            if (!polyline.isBlank()) {
                polylines.add(polyline);
            }
        }
        List<GeoPoint> points = PolylineParser.parseAll(polylines);
        if (points.size() < 2) {
            throw new AmapException("地图服务返回的路线为空");
        }
        return points;
    }

    /**
     * 把接口的错误码翻成能照着做的一句话。
     *
     * <p>密钥类型选错是最常见的一种失败，而接口原文只说「key 与平台不匹配」，看不出该去改什么。
     */
    private static String describeFailure(String info, String infocode) {
        String detail = info.isBlank() ? "未知错误" : info;
        if ("USERKEY_PLAT_NOMATCH".equals(info) || "10009".equals(infocode)) {
            return "地图密钥类型不对：请在高德控制台申请「Web 服务」类型的 Key，"
                    + "而不是 Web 端(JS API)或 Android/iOS 的 Key";
        }
        if ("INVALID_USER_KEY".equals(info) || "10001".equals(infocode)) {
            return "地图密钥无效，请检查是否复制完整";
        }
        if (info.startsWith("DAILY_QUERY_OVER_LIMIT") || "10003".equals(infocode)) {
            return "地图密钥今日调用次数已用完，请明天再试或更换密钥";
        }
        return "地图服务返回失败：" + detail;
    }

    /**
     * 坐标格式化为「经度,纬度」。
     *
     * <p>两个陷阱同时踩在这一行上：**经度在前**，以及必须固定区域设置——中文 Windows 下
     * {@code %f} 会输出逗号作小数点，URL 会被切成两段参数，请求直接废掉。
     */
    private static String coordinate(GeoPoint point) {
        return String.format(Locale.ROOT, "%.6f,%.6f", point.lng(), point.lat());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String preview(String body) {
        String trimmed = body == null ? "" : body.strip();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80) + "…";
    }
}
