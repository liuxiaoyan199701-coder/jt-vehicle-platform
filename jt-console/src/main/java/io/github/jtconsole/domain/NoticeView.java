package io.github.jtconsole.domain;

import io.github.jtconsole.ai.briefing.DashboardFinding;
import java.util.List;
import java.util.Map;

/**
 * 呈现给一个人的一条通知。
 *
 * <p>与 {@link Notice} 的区别有两处：JSON 列在这里已经解析成结构（前端直接用），
 * 以及多了 {@link #read()}——**已读是每人一份**，同一条通知对甲已读、对乙仍是未读。
 *
 * <p>字段形状与 {@code BriefingItem} 对齐，因为它们展示的是同一件事：
 * 前端两处可以共用同一套图标、配色与跳转逻辑。
 */
public record NoticeView(
        long id,
        DashboardFinding.Category category,
        DashboardFinding.Severity severity,
        String summary,
        Map<String, Object> facts,
        List<String> deviceIds,
        DashboardFinding.Link link,
        String createdAt,
        boolean read) {
}
