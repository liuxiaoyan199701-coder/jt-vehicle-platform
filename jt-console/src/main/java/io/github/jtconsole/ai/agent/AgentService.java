package io.github.jtconsole.ai.agent;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.ai.tool.ActionTools;
import io.github.jtconsole.ai.tool.DiagnosticsTools;
import io.github.jtconsole.ai.tool.FleetTools;
import io.github.jtconsole.ai.tool.GeoTools;
import io.github.jtconsole.ai.tool.LogTools;
import io.github.jtconsole.ai.tool.OperationsTools;
import io.github.jtconsole.ai.tool.RecordingTools;
import io.github.jtconsole.ai.tool.ToolRoundBudget;
import io.github.jtconsole.ai.tool.ToolSession;
import io.github.jtconsole.ai.tool.ViewTools;
import io.github.jtconsole.ai.tool.WaybillTools;
import io.github.jtconsole.ai.view.ViewBudget;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.security.AuthorizedPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 驱动一次对话：装配提示与工具，消费模型的流式响应，把它映射成推给前端的事件。
 *
 * <p>工具调用循环由 Spring AI 自己完成，这里不再手写——本类只负责「进去之前准备什么、出来之后
 * 怎么呈现」。工具执行过程中的事件由工具自己经 {@link ToolSession} 里的通道推送。
 *
 * <p>以阻塞方式消费框架返回的 {@code Flux}：控制台是 Spring MVC，一次对话独占一个工作线程，
 * 并发上限由线程池封顶。引入响应式服务器只为一个接口不划算。
 */
@Service
public class AgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentService.class);

    /**
     * 用 ObjectProvider 而不是直接注入：未配置模型时框架不会装配 ChatModel，直接注入会让整个
     * 应用起不来。而 {@code @ConditionalOnBean} 在用户 bean 上判定自动配置 bean 是不可靠的——
     * 自动配置在用户配置之后处理。运行时判定既简单又没有顺序陷阱。
     */
    private final ObjectProvider<ChatModel> chatModel;
    private final SystemPromptFactory prompts;
    private final BusinessDateService dates;
    private final ConsoleProperties properties;
    private final List<ToolCallback> toolCallbacks;

    public AgentService(
            ObjectProvider<ChatModel> chatModel,
            SystemPromptFactory prompts,
            BusinessDateService dates,
            ConsoleProperties properties,
            FleetTools fleetTools,
            OperationsTools operationsTools,
            ObjectProvider<GeoTools> geoTools,
            RecordingTools recordingTools,
            WaybillTools waybillTools,
            DiagnosticsTools diagnosticsTools,
            LogTools logTools,
            ActionTools actionTools,
            ViewTools viewTools) {
        this.chatModel = chatModel;
        this.prompts = prompts;
        this.dates = dates;
        this.properties = properties;
        List<ToolCallback> callbacks = new ArrayList<>(Arrays.asList(ToolCallbacks.from(
                fleetTools, operationsTools, recordingTools, waybillTools, diagnosticsTools,
                logTools, actionTools, viewTools)));
        // 地名检索依赖高德 Web 服务 key；未配置时 GeoTools 本身不装配，AI 也看不到该工具。
        geoTools.ifAvailable(tool -> callbacks.addAll(Arrays.asList(ToolCallbacks.from(tool))));
        this.toolCallbacks = List.copyOf(callbacks);
    }

    /**
     * 跑完一次对话，期间通过 {@code sink} 推送事件。
     *
     * @param history 前端送来的完整历史，最后一条应当是本次的用户提问
     * @return 本轮的最终回答与用量，供调用方留痕与计量
     */
    public Result chat(
            AuthorizedPrincipal principal,
            ConfirmationPolicy policy,
            List<Turn> history,
            AgentEventSink sink) {

        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            throw new AiUnavailableException("平台未启用 AI 功能");
        }
        // 配额每轮新建：它计的是「这一次对话里推了几个视图 / 跑了几轮工具」，跨轮累计没有意义。
        ToolSession session = new ToolSession(
                principal, principal.scope(), dates.zoneId(), sink, policy,
                new ViewBudget(),
                new ToolRoundBudget(properties.getAi().getMaxToolRounds()));
        ChatClient client = ChatClient.builder(model).build();

        StringBuilder answer = new StringBuilder();
        int[] tokens = {0, 0};
        try {
            client.prompt()
                    .system(prompts.build(principal))
                    .messages(toMessages(history))
                    .toolCallbacks(toolCallbacks)
                    .toolContext(session.asContext())
                    .stream()
                    .chatResponse()
                    .toStream()
                    .forEach(response -> consume(response, answer, tokens, sink));
        } catch (RuntimeException failure) {
            if (sink.cancelled()) {
                // 用户主动中止，不是故障，也不该报错给已经离开的页面。
                LOGGER.debug("对话被用户中止", failure);
                return new Result(answer.toString(), tokens[0], tokens[1], true);
            }
            throw failure;
        }
        return new Result(answer.toString(), tokens[0], tokens[1], false);
    }

    private void consume(
            ChatResponse response, StringBuilder answer, int[] tokens, AgentEventSink sink) {
        if (sink.cancelled()) {
            // 抛出以中断 Flux 消费；上面的 catch 会把它识别为中止而不是故障。
            throw new CancelledException();
        }
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            // 用量是累计值而不是增量，取最大值避免最后一个空 chunk 把它清零。
            tokens[0] = Math.max(tokens[0], intValue(usage.getPromptTokens()));
            tokens[1] = Math.max(tokens[1], intValue(usage.getCompletionTokens()));
        }
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }
        String text = response.getResult().getOutput().getText();
        if (text != null && !text.isEmpty()) {
            answer.append(text);
            sink.emit(AiEvent.delta(text));
        }
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private List<Message> toMessages(List<Turn> history) {
        int budget = properties.getAi().getHistoryCharBudget();
        List<Turn> trimmed = trim(history, budget);
        List<Message> messages = new ArrayList<>(trimmed.size());
        for (Turn turn : trimmed) {
            messages.add(turn.user()
                    ? new UserMessage(turn.content())
                    : new AssistantMessage(turn.content()));
        }
        return messages;
    }

    /**
     * 按字符预算从最旧开始成对丢弃，保持角色交替合法，永远保留最后一条用户消息。
     *
     * <p>不做更精细的 token 计数：字符数是个足够好的近似，而预算本身留了很大余量——真正的目的
     * 是防止历史无限增长，不是精确贴着窗口上限用满。
     */
    static List<Turn> trim(List<Turn> history, int charBudget) {
        int total = history.stream().mapToInt(turn -> turn.content().length()).sum();
        if (total <= charBudget || history.size() <= 1) {
            return history;
        }
        List<Turn> kept = new ArrayList<>(history);
        while (total > charBudget && kept.size() > 1) {
            total -= kept.getFirst().content().length();
            kept.removeFirst();
        }
        // 丢到只剩一条时它必须是用户消息，否则模型收到一个以 assistant 开头的对话。
        while (kept.size() > 1 && !kept.getFirst().user()) {
            total -= kept.getFirst().content().length();
            kept.removeFirst();
        }
        return kept;
    }

    /** 前端送来的一轮对话。只带最终文本，不带历史轮的工具调用——那些只在当轮循环内存在。 */
    public record Turn(boolean user, String content) {
        public Turn {
            content = content == null ? "" : content;
        }
    }

    /**
     * @param cancelled 用户中途关闭了页面。已发生的模型调用仍要计量，但不必再推送任何事件
     */
    public record Result(String answer, int promptTokens, int completionTokens, boolean cancelled) {
    }

    /** AI 功能未启用。控制器据此给出明确提示，而不是让用户对着一个空白的对话框。 */
    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message) {
            super(message);
        }
    }

    /** 当前部署是否装配了模型。控制器与前端据此决定要不要暴露 AI 入口。 */
    public boolean available() {
        return chatModel.getIfAvailable() != null;
    }

    /** 用于打断流式消费的内部信号，不对外暴露。 */
    static final class CancelledException extends RuntimeException {
        CancelledException() {
            super("对话已被用户中止", null, false, false);
        }
    }
}
