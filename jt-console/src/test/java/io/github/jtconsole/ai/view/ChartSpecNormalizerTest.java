package io.github.jtconsole.ai.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 图表描述的整理规则。
 *
 * <p>硬拒的边界是「有没有歧义」：长度对不齐不知道少的是哪一天，必须拒；超长的名字截断即可，
 * 拒绝只是白烧一轮对话。
 */
class ChartSpecNormalizerTest {

    private static ChartSpec spec(List<String> categories, ChartSpec.Series... series) {
        return new ChartSpec("line", "近三日里程", "get_daily_stats 粤B12345",
                categories, List.of(series), false);
    }

    @Test
    void acceptsAWellFormedSpec() {
        ChartSpecNormalizer.Result result = ChartSpecNormalizer.normalize(
                spec(List.of("08-15", "08-16", "08-17"),
                        new ChartSpec.Series("里程", null, "km", List.of(38.6, 12.4, 0.0))));

        assertThat(result.ok()).isTrue();
        assertThat(result.spec().series()).hasSize(1);
        assertThat(result.spec().stacked()).isFalse();
    }

    /**
     * 空值必须原样放行。
     *
     * <p>设备那天没上报是**断线**，不是「跑了 0 公里」。归零会造出「周末里程掉到 0」的假象，
     * 而且没人会怀疑到是这里填的。
     */
    @Test
    void keepsNullsInsteadOfCoercingThemToZero() {
        ChartSpecNormalizer.Result result = ChartSpecNormalizer.normalize(
                spec(List.of("08-15", "08-16", "08-17"),
                        new ChartSpec.Series("里程", null, "km", Arrays.asList(38.6, null, 12.4))));

        assertThat(result.ok()).isTrue();
        assertThat(result.spec().series().getFirst().data()).containsExactly(38.6, null, 12.4);
    }

    /** 长度对不齐有歧义——不知道少的是哪一天，补在头还是尾是两张不同的图。 */
    @Test
    void rejectsMismatchedLengthsAndShowsAnExample() {
        ChartSpecNormalizer.Result result = ChartSpecNormalizer.normalize(
                spec(List.of("08-15", "08-16", "08-17"),
                        new ChartSpec.Series("里程", null, "km", List.of(38.6, 12.4))));

        assertThat(result.ok()).isFalse();
        assertThat(result.problem()).contains("有 2 个值").contains("categories 有 3 项");
        // 回告必须带示例——模型对着示例改的成功率远高于对着字段清单。
        assertThat(result.problem()).contains("正确形状示例").contains("\"chartType\"");
    }

    @Test
    void rejectsChartTypesOutsideTheWhitelist() {
        ChartSpecNormalizer.Result result = ChartSpecNormalizer.normalize(
                new ChartSpec("sankey", "标题", "来源", List.of("a"),
                        List.of(new ChartSpec.Series("s", null, "", List.of(1.0))), false));

        assertThat(result.ok()).isFalse();
        assertThat(result.problem()).contains("chartType 只能是");
    }

    /** 来源必填：平台无法校验数值真伪，归因是用户唯一能自行核对的抓手。 */
    @Test
    void rejectsAChartWithoutAStatedSource() {
        ChartSpecNormalizer.Result result = ChartSpecNormalizer.normalize(
                new ChartSpec("line", "标题", "  ", List.of("a"),
                        List.of(new ChartSpec.Series("s", null, "", List.of(1.0))), false));

        assertThat(result.ok()).isFalse();
        assertThat(result.problem()).contains("source");
    }

    @Test
    void rejectsNonFiniteNumbers() {
        ChartSpecNormalizer.Result result = ChartSpecNormalizer.normalize(
                spec(List.of("a"), new ChartSpec.Series("s", null, "", List.of(Double.NaN))));

        assertThat(result.ok()).isFalse();
        assertThat(result.problem()).contains("非法数值");
    }

    /** 超量硬拒而不是截断：截断会画出一张只有前 60 天的图，用户看不出来被截过。 */
    @Test
    void rejectsTooManyCategoriesInsteadOfTruncating() {
        List<String> many = java.util.stream.IntStream
                .range(0, ChartSpecNormalizer.MAX_CATEGORIES + 1)
                .mapToObj(String::valueOf).toList();
        List<Double> data = many.stream().map(x -> 1.0).toList();

        ChartSpecNormalizer.Result result = ChartSpecNormalizer.normalize(
                spec(many, new ChartSpec.Series("s", null, "", data)));

        assertThat(result.ok()).isFalse();
        assertThat(result.problem()).contains("最多 " + ChartSpecNormalizer.MAX_CATEGORIES);
    }

    /** 饼图的分类即扇区，多系列没有意义——不要让它复用多系列语义。 */
    @Test
    void rejectsMultipleSeriesForAPieChart() {
        ChartSpecNormalizer.Result result = ChartSpecNormalizer.normalize(
                new ChartSpec("pie", "占比", "来源", List.of("A", "B"),
                        List.of(new ChartSpec.Series("s1", null, "", List.of(1.0, 2.0)),
                                new ChartSpec.Series("s2", null, "", List.of(3.0, 4.0))), false));

        assertThat(result.ok()).isFalse();
        assertThat(result.problem()).contains("pie 时只能有 1 条 series");
    }

    /** 超长只截断不拒绝：截断无歧义，而 200 字的系列名会把图例撑爆——那同样是「塌」。 */
    @Test
    void clipsOverlongLabelsRatherThanRejecting() {
        ChartSpecNormalizer.Result result = ChartSpecNormalizer.normalize(
                new ChartSpec("line", "标".repeat(100), "源".repeat(200), List.of("类".repeat(50)),
                        List.of(new ChartSpec.Series("名".repeat(100), null, "", List.of(1.0))),
                        false));

        assertThat(result.ok()).isTrue();
        assertThat(result.spec().title().length()).isLessThanOrEqualTo(40);
        assertThat(result.spec().series().getFirst().name().length()).isLessThanOrEqualTo(24);
        assertThat(result.spec().categories().getFirst().length()).isLessThanOrEqualTo(16);
    }
}
