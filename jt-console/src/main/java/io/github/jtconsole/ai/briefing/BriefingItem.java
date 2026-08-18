package io.github.jtconsole.ai.briefing;

import java.util.List;
import java.util.Map;

/**
 * 一条最终呈现在看板上的要点。
 *
 * <p>与 {@link DashboardFinding} 的区别只有 {@code text}：那是模型改写过的措辞，
 * 其余字段全部原样取自候选发现。**数字没有经过模型**。
 *
 * <p>{@code deviceIds} 留到这一层是为了读取时按数据范围过滤——简报按租户生成并缓存，
 * 而看它的人可能只管几个车队。
 */
public record BriefingItem(
        String id,
        DashboardFinding.Category category,
        DashboardFinding.Severity severity,
        String text,
        Map<String, Object> facts,
        List<String> deviceIds,
        DashboardFinding.Link link) {

    /** 是否是租户级聚合结论。这类无法按数据范围部分过滤，范围不全时整条不给。 */
    public boolean aggregate() {
        return deviceIds == null || deviceIds.isEmpty();
    }
}
