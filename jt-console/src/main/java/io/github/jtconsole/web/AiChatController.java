package io.github.jtconsole.web;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.agent.AgentService;
import io.github.jtconsole.ai.agent.AiEvent;
import io.github.jtconsole.ai.agent.RecordingEventSink;
import io.github.jtconsole.ai.quota.AiQuotaService;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.repository.AiConversationRepository;
import io.github.jtconsole.repository.AiUsageRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 助手的对话接口。
 *
 * <p>用 SSE 而不是复用 {@code /ws/live}：那条 WebSocket 是全局广播 + 严格全局序 + 慢消费者即杀，
 * 把 token 流塞进去会拖慢所有车辆的位置推送，最坏还会把用户的实时监控地图一起挤断线。
 *
 * <p>必须显式标注 {@link RequirePermission}：未标注的 POST 会落进「仅平台管理员」的兜底，
 * 租户用户会全部 403——不是安全问题，但排查起来很绕。
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiChatController.class);

    private final AgentService agent;
    private final AiQuotaService quotas;
    private final AiUsageRepository usage;
    private final AiConversationRepository conversations;
    private final ConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public AiChatController(
            AgentService agent,
            AiQuotaService quotas,
            AiUsageRepository usage,
            AiConversationRepository conversations,
            ConsoleProperties properties,
            ObjectMapper objectMapper,
            ExecutorService aiExecutor) {
        this.agent = agent;
        this.quotas = quotas;
        this.usage = usage;
        this.conversations = conversations;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = aiExecutor;
    }

    /** 前端据此决定要不要显示 AI 入口，以及本月还剩多少次。 */
    @GetMapping("/status")
    @RequirePermission(Permissions.AI_REPORT_VIEW)
    public ApiResponse<AiStatus> status(AuthorizedPrincipal principal) {
        AiQuotaService.Snapshot snapshot = quotas.snapshot(principal);
        return ApiResponse.ok(new AiStatus(
                agent.available(), snapshot.used(), snapshot.limit(), snapshot.month()));
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission(Permissions.AI_CHAT)
    public SseEmitter chat(@RequestBody ChatRequest request, AuthorizedPrincipal principal) {
        SseEmitter emitter = new SseEmitter(properties.getAi().getStreamTimeout().toMillis());

        if (!agent.available()) {
            finishWith(emitter, AiEvent.error("AI_DISABLED", "平台未启用 AI 功能"));
            return emitter;
        }
        List<AgentService.Turn> history = toTurns(request);
        if (history.isEmpty()) {
            finishWith(emitter, AiEvent.error("AI_EMPTY_REQUEST", "请输入你的问题"));
            return emitter;
        }

        AiQuotaService.Snapshot snapshot;
        try {
            snapshot = quotas.enforceChatQuota(principal);
        } catch (RuntimeException quotaExceeded) {
            finishWith(emitter, AiEvent.error("AI_QUOTA_EXCEEDED", quotaExceeded.getMessage()));
            return emitter;
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(failure -> cancelled.set(true));

        long conversationId = resolveConversation(request, principal, history);
        SseSink sink = new SseSink(emitter, cancelled, objectMapper);
        try {
            executor.execute(() -> run(principal, conversationId, history, snapshot, sink, emitter));
        } catch (RejectedExecutionException full) {
            // 池满即拒而不是排队：排队只会让请求在队列里坐到超时，用户对着转圈等更久。
            finishWith(emitter, AiEvent.error("AI_BUSY", "当前对话较多，请稍后再试"));
        }
        return emitter;
    }

    /** 复用前端带回的会话，带不回来（新开对话）就建一个，标题取首问前 30 字。 */
    private long resolveConversation(
            ChatRequest request, AuthorizedPrincipal principal, List<AgentService.Turn> history) {
        Long existing = request.conversationId();
        if (existing != null && conversations.belongsTo(existing, principal.accountId())) {
            return existing;
        }
        String first = history.getLast().content();
        String title = first.length() > 30 ? first.substring(0, 30) : first;
        return conversations.create(principal.tenantId(), principal.accountId(), title);
    }

    /** 某账号最近的会话，用于刷新页面后接着聊。 */
    @GetMapping("/conversations")
    @RequirePermission(Permissions.AI_CHAT)
    public ApiResponse<List<Map<String, Object>>> conversations(AuthorizedPrincipal principal) {
        return ApiResponse.ok(conversations.findRecent(principal.accountId(), 20));
    }

    @GetMapping("/conversations/{id}")
    @RequirePermission(Permissions.AI_CHAT)
    public ApiResponse<List<Map<String, Object>>> messages(
            @PathVariable long id, AuthorizedPrincipal principal) {
        if (!conversations.belongsTo(id, principal.accountId())) {
            // 与「不存在」同一句话：区分二者会泄露别人有这么一段对话。
            return ApiResponse.error("4004", "会话不存在");
        }
        return ApiResponse.ok(conversations.findMessages(id, 200));
    }

    @DeleteMapping("/conversations/{id}")
    @RequirePermission(Permissions.AI_CHAT)
    public ApiResponse<Void> deleteConversation(
            @PathVariable long id, AuthorizedPrincipal principal) {
        conversations.delete(id, principal.accountId());
        return ApiResponse.ok(null);
    }

    /** 清空某个会话的消息，会话保留。用于「这段聊歪了，从头再来」。 */
    @DeleteMapping("/conversations/{id}/messages")
    @RequirePermission(Permissions.AI_CHAT)
    public ApiResponse<Void> clearMessages(
            @PathVariable long id, AuthorizedPrincipal principal) {
        conversations.clearMessages(id, principal.accountId());
        return ApiResponse.ok(null);
    }

    private void run(
            AuthorizedPrincipal principal,
            long conversationId,
            List<AgentService.Turn> history,
            AiQuotaService.Snapshot snapshot,
            SseSink sink,
            SseEmitter emitter) {
        String model = "";
        int prompt = 0;
        int completion = 0;
        try {
            sink.emit(AiEvent.meta(conversationId, model));
            // 包一层收集器：视图与动作卡片要随消息留痕，否则刷新页面后它们全部消失。
            RecordingEventSink recorder = new RecordingEventSink(sink);
            AgentService.Result result = agent.chat(
                    principal, ConfirmationPolicy.confirmEverything(), history, recorder);
            prompt = result.promptTokens();
            completion = result.completionTokens();
            persist(conversationId, history, result, recorder.toJson(objectMapper),
                    prompt, completion);
            if (!result.cancelled()) {
                sink.emit(AiEvent.usage(prompt, completion, snapshot.used() + 1, snapshot.limit()));
                sink.emit(AiEvent.done("stop"));
            }
            emitter.complete();
        } catch (RuntimeException failure) {
            LOGGER.warn("AI 对话失败", failure);
            sink.emit(describe(failure));
            emitter.complete();
        } finally {
            // 只要发生过模型调用就计量，哪怕中途失败——费用是照产生的。
            if (prompt > 0 || completion > 0) {
                recordUsage(principal, model, prompt, completion);
            }
        }
    }

    /**
     * 落一轮对话。中途被用户中止时也要落——已经产生的部分回答仍然是发生过的事，
     * 刷新页面后不该凭空消失。
     */
    private void persist(
            long conversationId, List<AgentService.Turn> history,
            AgentService.Result result, String toolTrace, int prompt, int completion) {
        try {
            conversations.appendMessage(
                    conversationId, "user", history.getLast().content(), null, 0, 0);
            // 回答为空但有视图时**仍然要落一条**：模型完全可能只出图不说话，
            // 只判 isBlank 的话那张图会永久消失，而且日志里什么都没有。
            if (!result.answer().isBlank() || toolTrace != null) {
                conversations.appendMessage(
                        conversationId, "assistant", result.answer(), toolTrace, prompt, completion);
            }
        } catch (RuntimeException failure) {
            // 留痕失败不该连累已经给出的回答，但要留告警——用户会发现刷新后少了一段。
            LOGGER.warn("AI 对话留痕失败，本轮未入库", failure);
        }
    }

    private void recordUsage(AuthorizedPrincipal principal, String model, int prompt, int completion) {
        try {
            usage.record(principal.tenantId(), principal.accountId(),
                    AiUsageRepository.KIND_CHAT, model, 0, prompt, completion,
                    quotas.currentMonth());
        } catch (RuntimeException failure) {
            // 计量失败不该连累已经成功的回答，但必须留下告警——它意味着少收了一次钱。
            LOGGER.error("AI 用量记账失败，本次调用未被计量", failure);
        }
    }

    private static AiEvent describe(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        if (name.contains("Timeout") || name.contains("Timed")) {
            return AiEvent.error("AI_UPSTREAM_TIMEOUT", "AI 服务响应超时，请稍后重试");
        }
        if (failure instanceof AgentService.AiUnavailableException) {
            return AiEvent.error("AI_DISABLED", failure.getMessage());
        }
        return AiEvent.error("AI_UPSTREAM_FAILED", "AI 服务暂时不可用，请稍后重试");
    }

    private static List<AgentService.Turn> toTurns(ChatRequest request) {
        List<AgentService.Turn> turns = new ArrayList<>();
        if (request == null || request.messages() == null) {
            return turns;
        }
        for (ChatMessage message : request.messages()) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            turns.add(new AgentService.Turn(
                    !"assistant".equalsIgnoreCase(message.role()), message.content()));
        }
        // 末条必须是用户提问，否则模型会对着自己上一句话继续说。
        while (!turns.isEmpty() && !turns.getLast().user()) {
            turns.removeLast();
        }
        return turns;
    }

    private void finishWith(SseEmitter emitter, AiEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(
                    objectMapper.writeValueAsString(event.data()), MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException ignored) {
            // 客户端已经走了，没什么可做的
        }
        emitter.complete();
    }

    /** 把事件写进 SSE 流。发送失败即视为客户端断开，后续调用静默丢弃。 */
    /**
     * 把事件写进 SSE 连接。
     *
     * <p>做成静态嵌套类而不是内部类，是为了能被单测直接构造——它需要的只有一个映射器，
     * 与控制器实例本身无关。
     */
    static final class SseSink implements AgentEventSink {

        private final SseEmitter emitter;
        private final AtomicBoolean cancelled;
        private final ObjectMapper objectMapper;

        SseSink(SseEmitter emitter, AtomicBoolean cancelled, ObjectMapper objectMapper) {
            this.emitter = emitter;
            this.cancelled = cancelled;
            this.objectMapper = objectMapper;
        }

        /**
         * 推送一个事件。
         *
         * <p><b>必须同步</b>：本方法被两个线程调用——文本增量来自跑模型的工作线程，而工具事件
         * （查询进度、动作提议、视图提议）来自框架执行工具回调的线程。底层的 {@code send} 不是
         * 线程安全的，并发写会让两个事件的字节交错，前端按 {@code \n\n} 分帧就会解出半个 JSON。
         *
         * <p>此前没炸只是因为「工具执行期间模型不产 token」这个时序巧合——那不是保证，
         * 而且视图提议同样从工具线程推，撞上的概率只会更高。
         */
        @Override
        public synchronized void emit(AiEvent event) {
            if (cancelled.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event.name()).data(
                        objectMapper.writeValueAsString(event.data()), MediaType.APPLICATION_JSON));
            } catch (IOException | RuntimeException disconnected) {
                cancelled.set(true);
            }
        }

        @Override
        public boolean cancelled() {
            return cancelled.get();
        }
    }

    public record ChatRequest(Long conversationId, List<ChatMessage> messages) {
    }

    public record ChatMessage(String role, String content) {
    }

    public record AiStatus(boolean enabled, long used, int limit, String month) {
    }
}
