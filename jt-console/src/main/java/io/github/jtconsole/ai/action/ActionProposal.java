package io.github.jtconsole.ai.action;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条待执行的动作提议。
 *
 * <p>{@code params} 是助手从用户话里提取出来、将要提交给既有接口的字段值。界面必须把它**完整**
 * 展示出来——确认这一步的价值有一半在于让用户看见「它把车牌听错了一个字」，只显示一句
 * 「是否创建车辆？」等于把这层价值丢掉了。
 *
 * @param requiresConfirmation 是否需要用户点确认。false 表示前端可直接执行，但仍要在对话里
 *                             留下一张已执行卡片——省掉的是点击，不是可见性
 */
public record ActionProposal(
        String proposalId,
        ActionType type,
        String title,
        String reason,
        Map<String, Object> params,
        boolean requiresConfirmation) {

    public ActionProposal {
        params = params == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public Map<String, Object> asEventData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("proposalId", proposalId);
        data.put("type", type.wireName());
        data.put("label", type.label());
        data.put("title", title);
        data.put("reason", reason);
        data.put("params", params);
        data.put("requiredPermission", type.requiredPermission());
        data.put("requiresConfirmation", requiresConfirmation);
        return data;
    }
}
