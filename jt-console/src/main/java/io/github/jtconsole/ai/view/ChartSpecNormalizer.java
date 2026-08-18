package io.github.jtconsole.ai.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把模型写来的图表描述整理成可渲染的形状。
 *
 * <p><b>硬拒的边界在于「有没有歧义」</b>——这条与动作提议那边的字段校验不一样，值得写清楚：
 *
 * <ul>
 *   <li>动作那边对 {@code tenantId: "admin"} 硬拒，是因为它**有歧义**：可能真的指向别的租户，
 *       猜错就写到别人家里去了。</li>
 *   <li>这里**长度对不齐必须硬拒**：categories 有 7 项而某条系列只有 6 个值时，
 *       不知道少的是哪一天——补在末尾还是开头是两种完全不同的图，而用户看不出来被补过。</li>
 *   <li>而**超长的标题与系列名只截断不拒绝**：截断无歧义，拒绝只是白烧一轮对话。</li>
 * </ul>
 *
 * <p><b>关于数字串</b>：{@code data} 声明成 {@code List<Double>}，模型写 {@code "38.6"} 这类
 * 纯数字串时由 JSON 层直接转成数值，到不了这里；而 {@code "1,234"}、{@code "85%"} 这种会在
 * **参数绑定阶段**就失败，同样到不了这里。也就是说本类不做数值文本的解析——把类型写进 schema
 * 让模型一开始就写对，比事后补救更有效，代价是那两种写法的报错不如这里的回告清楚。
 */
public final class ChartSpecNormalizer {

    /** 分类上限。再多在气泡宽度里就是一团糊，而截断会画出用户看不出来的残图。 */
    public static final int MAX_CATEGORIES = 60;
    /** 系列上限。超过这个数图例就比图还高了。 */
    public static final int MAX_SERIES = 6;
    private static final int MAX_TITLE_CHARS = 40;
    private static final int MAX_NAME_CHARS = 24;
    private static final int MAX_CATEGORY_CHARS = 16;

    private ChartSpecNormalizer() {
    }

    /** @param problem 为空表示通过；否则是给模型看的原因，**带一个微型示例** */
    public record Result(ChartSpec spec, String problem) {

        public boolean ok() {
            return spec != null;
        }

        static Result rejected(String problem) {
            return new Result(null, problem + "\n" + EXAMPLE);
        }
    }

    /**
     * 拒绝时附带的正确形状。
     *
     * <p>模型对着示例改的成功率远高于对着字段清单——在嵌套结构上这一点是决定性的。
     */
    private static final String EXAMPLE = """
            正确形状示例：
            {"chartType":"line","title":"近三日里程","source":"get_daily_stats 粤B12345 08-15~08-17",\
            "categories":["08-15","08-16","08-17"],\
            "series":[{"name":"里程","unit":"km","data":[38.6,null,12.4]}]}""";

    public static Result normalize(ChartSpec raw) {
        if (raw == null) {
            return Result.rejected("缺少图表描述。");
        }
        String chartType = raw.chartType() == null
                ? "" : raw.chartType().trim().toLowerCase(Locale.ROOT);
        if (!ChartSpec.CHART_TYPES.contains(chartType)) {
            return Result.rejected("chartType 只能是 " + String.join("、", ChartSpec.CHART_TYPES)
                    + "，收到的是「" + raw.chartType() + "」。");
        }
        if (raw.source() == null || raw.source().isBlank()) {
            return Result.rejected("必须填写 source，说明这些数值来自哪次查询（工具名 + 对象 + 时间范围）。"
                    + "它会显示在图下方供用户核对。");
        }
        List<String> categories = trimAll(raw.categories(), MAX_CATEGORY_CHARS);
        if (categories.isEmpty()) {
            return Result.rejected("categories 不能为空。");
        }
        if (categories.size() > MAX_CATEGORIES) {
            return Result.rejected("categories 最多 " + MAX_CATEGORIES + " 项，收到 "
                    + categories.size() + " 项。请缩小范围或先做聚合。");
        }
        if (raw.series() == null || raw.series().isEmpty()) {
            return Result.rejected("series 不能为空。");
        }
        if (raw.series().size() > MAX_SERIES) {
            return Result.rejected("series 最多 " + MAX_SERIES + " 条，收到 "
                    + raw.series().size() + " 条。");
        }
        // 饼图的语义与折线柱状完全不同：分类即扇区，多系列没有意义。不要试图让它复用多系列语义。
        if ("pie".equals(chartType) && raw.series().size() != 1) {
            return Result.rejected("chartType 为 pie 时只能有 1 条 series，"
                    + "categories 即各扇区名称。收到 " + raw.series().size() + " 条。");
        }

        List<ChartSpec.Series> series = new ArrayList<>();
        for (ChartSpec.Series candidate : raw.series()) {
            String name = clip(candidate.name(), MAX_NAME_CHARS);
            if (name.isBlank()) {
                return Result.rejected("每条 series 都要有 name。");
            }
            if (candidate.data() == null) {
                return Result.rejected("系列「" + name + "」缺少 data。");
            }
            if (candidate.data().size() != categories.size()) {
                return Result.rejected("系列「" + name + "」有 " + candidate.data().size()
                        + " 个值，但 categories 有 " + categories.size()
                        + " 项。请补齐，缺测的位置用 null 占位（null 会画成断线，0 会被读成真的跑了 0）。");
            }
            List<Double> data = new ArrayList<>(candidate.data().size());
            for (Double value : candidate.data()) {
                // null 原样保留——它表示「那天没数据」，与 0 是两回事。
                if (value == null) {
                    data.add(null);
                } else if (!Double.isFinite(value)) {
                    return Result.rejected("系列「" + name + "」里有非法数值（NaN 或无穷）。");
                } else {
                    data.add(value);
                }
            }
            String type = candidate.type() == null || candidate.type().isBlank()
                    ? null : candidate.type().trim().toLowerCase(Locale.ROOT);
            if (type != null && !ChartSpec.CHART_TYPES.contains(type)) {
                return Result.rejected("系列「" + name + "」的 type 只能是 "
                        + String.join("、", ChartSpec.CHART_TYPES) + "。");
            }
            series.add(new ChartSpec.Series(
                    name, type, clip(candidate.unit(), MAX_NAME_CHARS), data));
        }

        return new Result(new ChartSpec(
                chartType,
                clip(raw.title(), MAX_TITLE_CHARS),
                clip(raw.source(), MAX_TITLE_CHARS * 2),
                categories,
                List.copyOf(series),
                raw.stacked() != null && raw.stacked()), null);
    }

    private static List<String> trimAll(List<String> values, int max) {
        if (values == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            out.add(clip(value == null ? "" : value, max));
        }
        return out;
    }

    /** 截断是为了布局而不是安全：一个 200 字的系列名会把图例撑爆，同样是「塌」。 */
    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
