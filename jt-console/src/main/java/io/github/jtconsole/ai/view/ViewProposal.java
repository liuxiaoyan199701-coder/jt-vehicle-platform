package io.github.jtconsole.ai.view;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条视图提议。
 *
 * <p>{@code params} 只描述「看什么」——设备号、时间窗、通道号。**这里永远不出现接口地址、
 * 查询语句或渲染配置**：前端按自己持有的白名单决定调哪个接口、怎么画。模型控制看什么，
 * 不控制怎么取、怎么画。
 *
 * <p>{@code viewId} 服务端不存也不校验，纯粹是前端列表渲染的稳定标识——与动作提议的
 * proposalId 同一性质。用随机 id 而不是序号：历史还原的视图与新一轮的视图会同时存在，
 * 序号会撞。
 */
public record ViewProposal(
        String viewId,
        ViewType type,
        String title,
        Map<String, Object> params) {

    public ViewProposal {
        params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public Map<String, Object> asEventData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("viewId", viewId);
        data.put("type", type.wireName());
        data.put("label", type.label());
        data.put("title", title);
        data.put("presentation", type.presentation().name().toLowerCase(java.util.Locale.ROOT));
        data.put("params", params);
        data.put("requiredPermission", type.requiredPermission());
        return data;
    }
}
