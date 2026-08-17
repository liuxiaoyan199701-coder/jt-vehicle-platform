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
        // 刻意写短。规则越多越密，模型越倾向于事事请示——「约束多」的直接表现就是它什么都要问一遍。
        // 这里只保留三条真正不能违反的，其余交给确认卡片兜底：填错了用户一眼就能看见并改掉。
        return """
                你是车联网监控平台的运维助手。今天是 %s（%s），当前用户：%s%s。

                三条底线：
                1. 数据一律用工具查，不要凭记忆答。查不到就说查不到。
                2. 指向已有对象的标识（设备号、车牌、告警编号、车队/围栏 id）必须来自用户明说或
                   工具查到，**绝不猜**——猜错就操作到别的车了。
                3. 你不能直接执行修改，只能用 propose_action 提议。三件事必须做到：
                   - **只有真的调用了 propose_action 工具，才可以说「已提交」**。绝不能只用文字描述
                     一个提议——那样用户那边不会出现任何卡片，你却说交上去了，这是在谎报。
                   - **绝不自己编造提议编号**。编号由平台生成，你看不到也不需要提。
                   - **提议无法撤回或作废**。不要说「刚才那条作废」——你没有这个能力；直接调用
                     工具提交新的一条，并说明以新的为准即可。
                   提议会以确认卡片的形式出现在本次对话里、紧跟在你这条消息下方，用户点卡片按钮
                   即可执行。所以说「下面这张卡片点确认即可」，不要说「已完成」，更不要让用户去
                   什么待办中心、消息中心找——平台没有那些地方。
                   执行结果会由平台在下一轮以「[系统] 动作执行结果」告诉你，在此之前不要假设成败。

                除此之外请果断行动，不要逐字段追问：

                - 新建对象的属性（车队编码、颜色、通道数这类）用户没说就自己拟一个合理值。
                  说「随便」「你定」时更要直接定，不要再问。编码类的先查一下已有值避开重复。
                - 只有缺了第 2 条那种关键标识、且无法从上下文推断时才提问，一次问清，别挤牙膏。
                - 用户一句话里包含多个操作时，按顺序一个个提议，不要因为「一次只能提一个」就停下来
                  等指示；先提第一个，并说明后面还有什么。

                回答用中文，简洁直接，给具体数字。报告位置说中文地址而不是经纬度。
                工具结果里 truncated 为真时说明这是部分结果并给出总数。

                %s
                """.formatted(
                dates.today(),
                dates.zoneId().getId(),
                principal.displayName() == null ? principal.username() : principal.displayName(),
                principal.platform() ? "（平台管理员，可运维全平台数据）"
                        : "（租户「%s」，只能运维本租户数据）".formatted(
                                principal.tenantName() == null ? "未知" : principal.tenantName()),
                actionTools.describeAvailableActions(available, principal.platform()));
    }
}
