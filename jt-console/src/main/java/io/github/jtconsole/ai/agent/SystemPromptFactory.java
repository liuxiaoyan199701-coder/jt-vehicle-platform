package io.github.jtconsole.ai.agent;

import io.github.jtconsole.ai.action.ActionProposalService;
import io.github.jtconsole.ai.action.ActionType;
import io.github.jtconsole.ai.tool.ActionTools;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.security.AuthorizedPrincipal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 组装系统提示词。
 *
 * <p>刻意把「当前日期」「当前用户是谁」「能做哪些动作」写进提示词而不是让模型自己猜：模型不知道
 * 今天几号就会拿训练数据里的日期去算「昨天」，不知道有哪些合法动作名就会自己发明一个再吃一句
 * 「不支持的动作类型」，两者都是白白浪费一轮。
 */
@Component
public class SystemPromptFactory {

    private final BusinessDateService dates;
    private final ActionProposalService actions;
    private final ActionTools actionTools;

    public SystemPromptFactory(
            BusinessDateService dates,
            ActionProposalService actions,
            ActionTools actionTools) {
        this.dates = dates;
        this.actions = actions;
        this.actionTools = actionTools;
    }

    public String build(AuthorizedPrincipal principal) {
        List<ActionType> available = actions.availableTo(principal);
        return """
                你是车联网监控平台的运维助手，帮助用户查询平台数据并代其发起管理操作。

                ## 当前上下文
                - 平台业务日期：%s（时区 %s）
                - 当前用户：%s%s

                ## 硬性要求
                1. 所有数据都必须通过工具查询得到，**不要凭记忆或推测回答**。查不到就说查不到。
                2. 你看到的数据已经按当前用户的可见范围过滤过。查不到某台车时，说「未找到」即可，
                   不要推测它是否存在于别处。
                3. 涉及日期时先用 get_current_time 确认今天是几号，不要用你训练时的日期推算。
                4. 报告位置时给中文地址，而不是一串经纬度。工具已尽量附带 address 字段；
                   只有坐标时可用 resolve_address 转换。
                5. 你**不能直接执行任何修改操作**，只能用 propose_action 提议，由用户决定。
                   提议之后你无法得知结果，因此绝不要说「已完成」「已创建」，应说「已提交给你确认」。
                6. 关键字段（车牌、设备号、告警编号）必须来自用户明说或工具查询结果，
                   **绝不自行编造或猜测**。信息不全时向用户问清楚。

                ## 回答风格
                用中文，简洁直接。数字要给具体值而不是「比较多」。列举超过五条时先给结论再列要点。
                工具返回 truncated 为真时说明这是部分结果并给出总数，不要拿部分结果回答「一共多少」。

                %s
                """.formatted(
                dates.today(),
                dates.zoneId().getId(),
                principal.displayName() == null ? principal.username() : principal.displayName(),
                principal.platform() ? "（平台管理员，可运维全平台数据）"
                        : "（租户「%s」，只能运维本租户数据）".formatted(
                                principal.tenantName() == null ? "未知" : principal.tenantName()),
                actionTools.describeAvailableActions(available));
    }
}
