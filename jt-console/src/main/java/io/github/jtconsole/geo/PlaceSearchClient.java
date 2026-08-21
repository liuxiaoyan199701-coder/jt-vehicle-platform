package io.github.jtconsole.geo;

import io.github.jtconsole.config.ConsoleProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 地名/POI 转坐标，基于高德 Web 服务的 POI 搜索并以正向地理编码兜底。
 *
 * <p>高德 Web 服务返回 GCJ-02，而 jt-console 的围栏中心列与定位轨迹均使用 GCJ-02
 *（列名为 {@code center_gcj_lat}/{@code center_gcj_lng}）。因此这里刻意不做 WGS-84
 * 转换，返回值可以直接交给 {@code create_geofence}；给它再做一次转换反而会产生偏移。
 * 查询失败或没有命中时统一返回空列表，地名检索只是 AI 的辅助能力，不能拖垮对话。
 */
@Component
public class PlaceSearchClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaceSearchClient.class);
    private static final String BASE_URL = "https://restapi.amap.com";
    private static final String PLACE_PATH = "/v3/place/text";
    private static final String GEOCODE_PATH = "/v3/geocode/geo";
    private static final int MAX_RESULTS = 5;

    private final ConsoleProperties.Geo properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public PlaceSearchClient(ConsoleProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.getGeo();
        this.objectMapper = objectMapper;
        Duration timeout = this.properties.getRequestTimeout();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();
    }

    public boolean available() {
        return !key().isEmpty();
    }

    /**
     * 先查 POI，再用正向地理编码兜底。
     *
     * @param keyword 地名或 POI 关键词
     * @param city    可选城市限定
     * @return 最多五条 GCJ-02 候选；未配置 key、未命中或上游失败时为空
     */
    public List<PlaceCandidate> search(String keyword, String city) {
        if (!available() || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String query = keyword.trim();
        String region = blankToNull(city);
        List<PlaceCandidate> places = requestPlaces(query, region);
        return places.isEmpty() ? requestGeocodes(query, region) : places;
    }

    /** 便于单测断言高德 POI 响应解析，不需要联网。 */
    static List<PlaceCandidate> parsePlaces(ObjectMapper mapper, String body) {
        return parseItems(mapper, body, "pois", false);
    }

    /** 便于单测断言高德 geocode 响应解析，不需要联网。 */
    static List<PlaceCandidate> parseGeocodes(ObjectMapper mapper, String body) {
        return parseItems(mapper, body, "geocodes", true);
    }

    private List<PlaceCandidate> requestPlaces(String keyword, String city) {
        try {
            String body = client.get()
                    .uri(builder -> builder.path(PLACE_PATH)
                            .queryParam("key", key())
                            .queryParam("keywords", keyword)
                            .queryParamIfPresent("city", java.util.Optional.ofNullable(city))
                            .queryParam("offset", MAX_RESULTS)
                            .queryParam("page", 1)
                            .queryParam("extensions", "base")
                            .build())
                    .retrieve()
                    .body(String.class);
            return parsePlaces(objectMapper, body);
        } catch (RuntimeException failure) {
            LOGGER.warn("高德 POI 搜索失败，尝试正向地理编码兜底：{}", failure.getMessage());
            return List.of();
        }
    }

    private List<PlaceCandidate> requestGeocodes(String keyword, String city) {
        try {
            String body = client.get()
                    .uri(builder -> builder.path(GEOCODE_PATH)
                            .queryParam("key", key())
                            .queryParam("address", keyword)
                            .queryParamIfPresent("city", java.util.Optional.ofNullable(city))
                            .build())
                    .retrieve()
                    .body(String.class);
            return parseGeocodes(objectMapper, body);
        } catch (RuntimeException failure) {
            LOGGER.warn("高德正向地理编码失败，地名检索降级为空：{}", failure.getMessage());
            return List.of();
        }
    }

    private String key() {
        String key = properties.getAmapWebServiceKey();
        return key == null ? "" : key.trim();
    }

    private static List<PlaceCandidate> parseItems(
            ObjectMapper mapper, String body, String arrayName, boolean geocode) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (!"1".equals(root.path("status").asString(""))) {
                return List.of();
            }
            List<PlaceCandidate> candidates = new ArrayList<>();
            for (JsonNode item : root.path(arrayName)) {
                String location = item.path("location").asString("");
                double[] point = parseLocation(location);
                if (point == null) {
                    continue;
                }
                String name = text(item, "name");
                String address = text(item, geocode ? "formatted_address" : "address");
                if (address.isEmpty()) {
                    address = text(item, "formatted_address");
                }
                if (name.isEmpty()) {
                    name = address;
                }
                if (name.isEmpty()) {
                    continue;
                }
                candidates.add(new PlaceCandidate(name, address, point[0], point[1]));
                if (candidates.size() == MAX_RESULTS) {
                    break;
                }
            }
            return List.copyOf(candidates);
        } catch (RuntimeException malformed) {
            return List.of();
        }
    }

    /** 高德 location 是「经度,纬度」，候选对外统一为 [lat,lng] 语义。 */
    private static double[] parseLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String[] parts = location.trim().split(",", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            if (!Double.isFinite(lat) || !Double.isFinite(lng)
                    || lat < -90 || lat > 90 || lng < -180 || lng > 180
                    || (Math.abs(lat) < 0.000001D && Math.abs(lng) < 0.000001D)) {
                return null;
            }
            return new double[] {lat, lng};
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString("").trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record PlaceCandidate(String name, String address, double lat, double lng) {
    }
}
