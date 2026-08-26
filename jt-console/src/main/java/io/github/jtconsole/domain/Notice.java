package io.github.jtconsole.domain;

/**
 * 一条主动通知：已经落库的、够格打扰人的看板发现。
 *
 * <p>字段与 {@code DashboardFinding} 一一对应，只多两样：{@link #dedupKey()} 与
 * {@link #tenantId()}。**文案直接取发现的事实陈述，不过模型**——通知是无人监督时自动发出的，
 * 多一次模型调用就多一次幻觉与失败面；更实际的理由是模型会把几条发现归并成一条，
 * 归并之后就丢掉了逐条身份，而抑制完全依赖那个身份。
 *
 * <p>JSON 列在这一层保持原文，与仓储层其它记录一致：解析放在服务层做，
 * 仓储只负责列与行。
 *
 * @param tenantId       归属租户。{@code 0} 是平台级作用域的哨兵，与 {@code ai_report} 同一口径
 * @param dedupKey       去重键。设备类为 {@code 类别:设备号}，聚合类为 {@code 类别:发现id}，
 *                       **不是发现的展示 id**——那个会随排序漂移
 * @param facts          支撑数据的 JSON 原文，原样透传到前端
 * @param deviceIds      本条涉及的设备号 JSON 数组，读取时按数据范围过滤要用；聚合类为空数组
 * @param linkQuery      导航参数的 JSON 原文
 */
public record Notice(
        long id,
        long tenantId,
        String dedupKey,
        String category,
        String severity,
        String summary,
        String facts,
        String deviceIds,
        String linkRoute,
        String linkQuery,
        String linkLabel,
        String createdAt) {
}
