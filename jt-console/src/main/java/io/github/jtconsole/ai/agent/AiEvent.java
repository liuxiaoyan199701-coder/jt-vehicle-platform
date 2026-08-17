package io.github.jtconsole.ai.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推给前端的一个事件。{@link #name()} 即 SSE 的 {@code event:} 字段，{@link #data()} 序列化成
 * {@code data:} 字段。
 *
 * <p>{@link Kind#DONE} 与 {@link Kind#ERROR} 是互斥的终止事件——前端据此决定是收尾还是报错，
 * 两者同时出现会让界面状态无法判定。
 */
public record AiEvent(Kind kind, Map<String, Object> data) {

    public enum Kind {
        META, DELTA, TOOL, ACTION, USAGE, DONE, ERROR;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public AiEvent {
        // 不能用 Map.copyOf：它拒绝 null 值，而新会话的 conversationId 本来就是空的。
        data = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public String name() {
        return kind.wireName();
    }

    public boolean terminal() {
        return kind == Kind.DONE || kind == Kind.ERROR;
    }

    public static AiEvent meta(Long conversationId, String model) {
        return new AiEvent(Kind.META, of("conversationId", conversationId, "model", model));
    }

    public static AiEvent delta(String text) {
        return new AiEvent(Kind.DELTA, of("text", text));
    }

    /** 工具开始执行。{@code brief} 是给人看的一句话，不是给模型看的。 */
    public static AiEvent toolStart(String name, String brief) {
        return new AiEvent(Kind.TOOL, of("phase", "start", "name", name, "brief", brief));
    }

    public static AiEvent toolEnd(String name, boolean ok, String brief) {
        return new AiEvent(Kind.TOOL, of("phase", "end", "name", name, "ok", ok, "brief", brief));
    }

    /**
     * 一条待用户确认的动作提议。前端据 {@code type} 在**自己持有的**白名单里查对应接口，
     * 不使用服务端下发的路径——模型不该有能力指定调用哪个 URL。
     */
    public static AiEvent action(
            String proposalId,
            String type,
            String title,
            String reason,
            Map<String, Object> params,
            String requiredPermission) {
        return new AiEvent(Kind.ACTION, of(
                "proposalId", proposalId,
                "type", type,
                "title", title,
                "reason", reason,
                "params", params,
                "requiredPermission", requiredPermission));
    }

    public static AiEvent usage(int promptTokens, int completionTokens, long used, int limit) {
        return new AiEvent(Kind.USAGE, of(
                "promptTokens", promptTokens,
                "completionTokens", completionTokens,
                "monthlyUsed", used,
                "monthlyLimit", limit));
    }

    public static AiEvent done(String finishReason) {
        return new AiEvent(Kind.DONE, of("finishReason", finishReason));
    }

    /**
     * @param code    机器可判定的错误码，前端据此决定文案与是否可重试
     * @param message 已经可以直接展示给用户的中文说明，不含任何内部细节
     */
    public static AiEvent error(String code, String message) {
        return new AiEvent(Kind.ERROR, of("code", code, "message", message));
    }

    /** {@code Map.of} 不接受 null 值，而 conversationId 这类字段确实可能为空。 */
    private static Map<String, Object> of(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
