package io.github.jtconsole.ai.tool;

import io.github.jtconsole.geo.PlaceSearchClient;
import io.github.jtconsole.geo.PlaceSearchClient.PlaceCandidate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

/** AI 地名检索工具。只有配置高德 Web 服务 key 时才装配，避免暴露一个必然失败的工具。 */
@Component
@Conditional(GeoTools.KeyConfiguredCondition.class)
public class GeoTools {

    /** 空字符串也视为未配置；不能只用 ConditionalOnProperty，因为其默认会匹配空值。 */
    public static final class KeyConfiguredCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(
                ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("jt.console.geo.amap-web-service-key");
            boolean configured = key != null && !key.isBlank();
            return new ConditionOutcome(configured,
                    configured ? "高德 Web 服务 key 已配置" : "高德 Web 服务 key 未配置");
        }
    }

    private final ToolRunner runner;
    private final PlaceSearchClient places;

    public GeoTools(ToolRunner runner, PlaceSearchClient places) {
        this.runner = runner;
        this.places = places;
    }

    @Tool(name = "search_place",
            description = "把地名、村、小区、道路或 POI 关键词搜索为最多 5 条坐标候选，"
                    + "供创建电子围栏时作为圆心使用。返回的 lat/lng 是 GCJ-02（高德坐标），"
                    + "与平台围栏 centerGcjLat/centerGcjLng 完全一致，直接传给 propose_action 的 geofence_create，"
                    + "不要再做 WGS84/GCJ-02 转换，也不要把经纬度顺序颠倒。"
                    + "若返回多条候选，必须把名称、地址和坐标列给用户确认，绝不能自行挑选；"
                    + "确认后再提议创建围栏。查不到时如实告知用户，不要猜坐标。")
    String searchPlace(
            @ToolParam(description = "地名或 POI 关键词，例如：龙华东二村") String keyword,
            @ToolParam(description = "城市名，可选；用于缩小同名地点范围", required = false) String city,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "search_place", "搜索地点：" + keyword, () -> {
            if (keyword == null || keyword.isBlank()) {
                return ToolResults.error("请输入地名或 POI 关键词");
            }
            List<PlaceCandidate> candidates = places.search(keyword, city);
            if (candidates.isEmpty()) {
                return ToolResults.error("没有找到匹配的地点，请补充城市名或换一个关键词");
            }
            List<Map<String, Object>> rows = candidates.stream().map(GeoTools::brief).toList();
            Map<String, Object> result = new LinkedHashMap<>(ToolResults.page(
                    "candidates", rows, 5, rows.size()));
            result.put("coordinateSystem", "GCJ-02");
            result.put("note", "多候选时必须先向用户确认具体地点，再创建围栏；禁止自行选择。"
                    + "坐标可直接用于 geofence_create，不要再次转换。");
            return result;
        });
    }

    private static Map<String, Object> brief(PlaceCandidate candidate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", candidate.name());
        row.put("address", candidate.address());
        row.put("lat", candidate.lat());
        row.put("lng", candidate.lng());
        return row;
    }
}
