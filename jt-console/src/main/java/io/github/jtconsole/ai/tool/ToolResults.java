package io.github.jtconsole.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * 工具结果的裁剪与序列化。
 *
 * <p>不裁剪的后果不是「回答略长」而是「回答不了」：一次轨迹查询能取回上千个点，直接喂给模型
 * 既烧钱又撑爆上下文窗口，而模型本来也答不出「第 437 个点在哪」这种问题。所有列表类结果都要
 * 截断并明确标注，让模型知道自己看到的不是全部——否则它会拿着截断后的 20 条去回答「一共有多少」。
 */
public final class ToolResults {

    private ToolResults() {
    }

    /**
     * 截断列表并附上真实总数。
     *
     * @param total 截断前的真实数量，必须给准——模型报数时依赖它
     */
    public static Map<String, Object> page(String key, List<?> items, int limit, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<?> kept = items.size() > limit ? items.subList(0, limit) : items;
        result.put(key, kept);
        result.put("total", total);
        if (kept.size() < total) {
            result.put("truncated", true);
            result.put("note", "结果已截断，只返回前 " + kept.size() + " 条，总数见 total");
        }
        return result;
    }

    /**
     * 等距抽样，保留首尾。
     *
     * <p>用于轨迹这类「形状比逐点精度更重要」的数据：抽样后的折线仍能反映走向，而点数是可控的。
     */
    public static <T> List<T> sample(List<T> source, int max) {
        if (source.size() <= max || max <= 0) {
            return List.copyOf(source);
        }
        List<T> sampled = new ArrayList<>(max);
        double step = (source.size() - 1) / (double) (max - 1);
        for (int i = 0; i < max; i++) {
            sampled.add(source.get((int) Math.round(i * step)));
        }
        return sampled;
    }

    /** 结构化的失败。工具异常不该中断整个对话——把原因告诉模型，它可以换个方式再试。 */
    public static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    /**
     * 序列化并做字符数兜底。
     *
     * <p>前面的按条截断已经覆盖了绝大多数情况，这里是最后一道防线：某个字段异常长（比如一段
     * 很长的备注）也不至于把单次工具结果撑到失控。
     */
    public static String write(ObjectMapper mapper, Object value, int charLimit) {
        String json = mapper.writeValueAsString(value);
        if (json.length() <= charLimit) {
            return json;
        }
        // 截断后的 JSON 不再合法，所以整体换成一个合法的说明对象，而不是给模型半截 JSON。
        return mapper.writeValueAsString(Map.of(
                "error", "结果过大已被丢弃，请缩小查询范围（例如缩短时间跨度或增加筛选条件）",
                "sizeChars", json.length(),
                "limitChars", charLimit));
    }
}
