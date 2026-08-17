package io.github.jtconsole.web;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.agent.AgentService;
import io.github.jtconsole.ai.agent.AiEvent;
import io.github.jtconsole.ai.quota.AiQuotaService;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.repository.AiUsageRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final ConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public AiChatController(
            AgentService agent,
            AiQuotaService quotas,
            AiUsageRepository usage,
            ConsoleProperties properties,
            ObjectMapper objectMapper,
            ExecutorService aiExecutor) {
        this.agent = agent;
        this.quotas = quotas;
        this.usage = usage;
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

        SseSink sink = new SseSink(emitter, cancelled);
        try {
            executor.execute(() -> run(principal, history, snapshot, sink, emitter));
        } catch (RejectedExecutionException full) {
            // 池满即拒而不是排队：排队只会让请求在队列里坐到超时，用户对着转圈等更久。
            finishWith(emitter, AiEvent.error("AI_BUSY", "当前对话较多，请稍后再试"));
        }
        return emitter;
    }

    private void run(
            AuthorizedPrincipal principal,
            List<AgentService.Turn> history,
            AiQuotaService.Snapshot snapshot,
            SseSink sink,
            SseEmitter emitter) {
        String model = "";
        int prompt = 0;
        int completion = 0;
        try {
            sink.emit(AiEvent.meta(null, model));
            AgentService.Result result = agent.chat(
                    principal, ConfirmationPolicy.confirmEverything(), history, sink);
            prompt = result.promptTokens();
            completion = result.completionTokens();
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
    private final class SseSink implements AgentEventSink {

        private final SseEmitter emitter;
        private final AtomicBoolean cancelled;

        private SseSink(SseEmitter emitter, AtomicBoolean cancelled) {
            this.emitter = emitter;
            this.cancelled = cancelled;
        }

        @Override
        public void emit(AiEvent event) {
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
