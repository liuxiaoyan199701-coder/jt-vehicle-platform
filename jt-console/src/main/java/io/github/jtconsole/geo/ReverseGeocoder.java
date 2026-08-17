package io.github.jtconsole.geo;

import io.github.jtconsole.config.ConsoleProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 坐标转地址（逆地理编码），基于高德 Web 服务接口。
 *
 * <p>为什么必须有：一句「车辆当前位置 31.2304, 121.4737」对运营人员没有任何意义，
 * 「浦东新区世纪大道」才是能据此行动的信息。AI 回答与运营简报里凡是提到位置的地方都该带地址。
 *
 * <p>三条设计取舍：
 * <ul>
 *   <li>库里存的 {@code gcj_lat}/{@code gcj_lng} 本来就是 GCJ-02，与高德坐标系一致，直接送即可，
 *       不需要再转换。送 WGS-84 会偏出几百米——足以把地址报到隔壁街区。</li>
 *   <li>带缓存：车辆长时间停在同一个点会反复查同一坐标，而高德按调用次数计配额。缓存键把坐标
 *       截到小数点后四位（约 11 米），停车场里的轻微漂移会命中同一条缓存。</li>
 *   <li>失败即降级：拿不到地址就只回坐标，绝不让地址查询失败连累整个查询——地址是锦上添花，
 *       位置本身才是主体。</li>
 * </ul>
 */
@Component
public class ReverseGeocoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReverseGeocoder.class);
    private static final String ENDPOINT = "https://restapi.amap.com/v3/geocode/regeo";
    /** 高德批量逆地理单次上限 20 个坐标。 */
    private static final int BATCH_LIMIT = 20;

    private final ConsoleProperties.Geo properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final Map<String, String> cache;

    public ReverseGeocoder(ConsoleProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.getGeo();
        this.objectMapper = objectMapper;
        Duration timeout = this.properties.getRequestTimeout();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(ENDPOINT).requestFactory(factory).build();
        int capacity = Math.max(64, this.properties.getAddressCacheSize());
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > capacity;
            }
        });
    }

    public boolean available() {
        return !properties.getAmapWebServiceKey().isBlank();
    }

    /**
     * 单点查询。
     *
     * @return 地址；未配置 key、坐标无效或查询失败时为空
     */
    public Optional<String> address(Double gcjLat, Double gcjLng) {
        if (!available() || invalid(gcjLat, gcjLng)) {
            return Optional.empty();
        }
        String key = cacheKey(gcjLat, gcjLng);
        String cached = cache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? Optional.empty() : Optional.of(cached);
        }
        List<String> resolved = query(List.of(location(gcjLat, gcjLng)));
        String address = resolved.isEmpty() ? "" : resolved.getFirst();
        // 失败也写进缓存（空串），避免对同一个查不出地址的坐标反复打高德。
        cache.put(key, address);
        return address.isEmpty() ? Optional.empty() : Optional.of(address);
    }

    /**
     * 批量查询，按输入顺序返回，查不到的位置为 {@code null}。
     *
     * <p>批量的意义不只是省往返，更是省配额：一次请求最多带 20 个坐标，对一屏车辆做地址标注时
     * 是 1 次调用而不是 20 次。
     */
    public List<String> addresses(List<double[]> coordinates) {
        List<String> results = new ArrayList<>(Collections.nCopies(coordinates.size(), null));
        if (!available()) {
            return results;
        }
        List<Integer> pendingIndexes = new ArrayList<>();
        List<String> pendingLocations = new ArrayList<>();
        for (int i = 0; i < coordinates.size(); i++) {
            double[] point = coordinates.get(i);
            if (point == null || invalid(point[0], point[1])) {
                continue;
            }
            String cached = cache.get(cacheKey(point[0], point[1]));
            if (cached != null) {
                results.set(i, cached.isEmpty() ? null : cached);
                continue;
            }
            pendingIndexes.add(i);
            pendingLocations.add(location(point[0], point[1]));
        }
        for (int from = 0; from < pendingLocations.size(); from += BATCH_LIMIT) {
            int to = Math.min(from + BATCH_LIMIT, pendingLocations.size());
            List<String> chunk = pendingLocations.subList(from, to);
            List<String> resolved = query(chunk);
            for (int i = 0; i < chunk.size(); i++) {
                int target = pendingIndexes.get(from + i);
                String address = i < resolved.size() ? resolved.get(i) : "";
                double[] point = coordinates.get(target);
                cache.put(cacheKey(point[0], point[1]), address);
                results.set(target, address.isEmpty() ? null : address);
            }
        }
        return results;
    }

    private List<String> query(List<String> locations) {
        try {
            String body = client.get()
                    .uri(builder -> builder
                            .queryParam("key", properties.getAmapWebServiceKey())
                            .queryParam("location", String.join("|", locations))
                            .queryParam("batch", locations.size() > 1)
                            .queryParam("extensions", "base")
                            .build())
                    .retrieve()
                    .body(String.class);
            return parse(body, locations.size());
        } catch (RuntimeException failure) {
            LOGGER.warn("逆地理编码失败，本次降级为只返回坐标", failure);
            return List.of();
        }
    }

    private List<String> parse(String body, int expected) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(body);
        if (!"1".equals(root.path("status").asString(""))) {
            // 最常见的是 USERKEY_PLAT_NOMATCH：把 JS API key 填成了 Web 服务 key。
            LOGGER.warn("逆地理编码被拒绝：info={} infocode={}",
                    root.path("info").asString(""), root.path("infocode").asString(""));
            return List.of();
        }
        List<String> addresses = new ArrayList<>(expected);
        JsonNode single = root.path("regeocode");
        if (single.isObject()) {
            addresses.add(single.path("formatted_address").asString(""));
            return addresses;
        }
        for (JsonNode node : root.path("regeocodes")) {
            addresses.add(node.path("formatted_address").asString(""));
        }
        return addresses;
    }

    private static String location(double lat, double lng) {
        // 高德的顺序是「经度,纬度」，与常见的 lat,lng 相反，写反了会把地址报到另一个半球。
        return "%.6f,%.6f".formatted(lng, lat);
    }

    private static String cacheKey(double lat, double lng) {
        return "%.4f,%.4f".formatted(lat, lng);
    }

    private static boolean invalid(Double lat, Double lng) {
        return lat == null || lng == null
                || (Math.abs(lat) < 0.000001D && Math.abs(lng) < 0.000001D)
                || Math.abs(lat) > 90.0D || Math.abs(lng) > 180.0D;
    }
}
