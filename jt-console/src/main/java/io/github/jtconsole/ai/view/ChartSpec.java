package io.github.jtconsole.ai.view;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 一张图表的描述。这是**受约束的中间表示**，不是渲染库的配置。
 *
 * <p><b>绝不接受模型下发原始渲染配置。</b>理由不是「模型不该管渲染」这种类比，而是三个具体的
 * 注入面：提示框的格式化函数在默认模式下返回值直接进 innerHTML；标记点的符号字段支持
 * {@code image://} 协议、可向任意外部地址发请求（一条数据外传信道）；图形组件可塞任意元素。
 * 本项目的 markdown 尚且要过消毒，直通渲染配置是同一个洞开得更大。
 *
 * <p>模型能表达的：画什么形状、分几类、几条系列、各自什么单位。模型不能表达的：颜色、图例位置、
 * 坐标轴分配、平滑、网格——那些是渲染细节，由界面统一控制。
 *
 * @param source 数据出处，**必填**且会原样显示为图表脚注。平台无法校验模型转述数值的真伪，
 *     这个字段的作用是让用户能自己核对——一张图比一句话看起来权威得多，文字答错用户还会怀疑，
 *     图画错了用户直接截图发出去
 */
public record ChartSpec(
        String chartType,
        String title,
        String source,
        List<String> categories,
        List<Series> series,
        Boolean stacked) {

    /**
     * 一条数据系列。
     *
     * @param unit 本系列的单位。**按系列给而不按图给**——「里程 + 告警数」是最想画的组合，
     *     双轴是常态。但模型只写单位、不写轴索引：界面按单位去重后自动分配最多两条轴，
     *     轴的分配属于渲染细节
     * @param data 数值。**允许为空**：设备那天没上报是「断线」，不是「跑了 0 公里」，
     *     两者语义完全不同，归零会造出假象且没人会怀疑到是界面填的
     */
    public record Series(String name, String type, String unit, List<Double> data) {

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public static Series fromJson(
                @JsonProperty("name") String name,
                @JsonProperty("type") String type,
                @JsonProperty("unit") String unit,
                @JsonProperty("data") List<Double> data) {
            return new Series(name, type, unit, data);
        }
    }

    /** 允许的图形。白名单，不在其中的一律回告。 */
    public static final List<String> CHART_TYPES = List.of("line", "bar", "pie");

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static ChartSpec fromJson(
            @JsonProperty("chartType") String chartType,
            @JsonProperty("title") String title,
            @JsonProperty("source") String source,
            @JsonProperty("categories") List<String> categories,
            @JsonProperty("series") List<Series> series,
            @JsonProperty("stacked") Boolean stacked) {
        return new ChartSpec(chartType, title, source, categories, series, stacked);
    }
}
