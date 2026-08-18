package io.github.jtconsole.ai.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * 转发事件的同时，把需要留痕的那几类留一份。
 *
 * <p>做成装饰器而不是往 {@code SseSink} 里加字段：推送与留痕是两件事，混在一起会让
 * 「客户端断开后还要不要继续收集」这类问题变得含糊。装饰器里答案很清楚——**照收**，
 * 因为用户下次打开这段会话仍然该看到当时的内容。
 *
 * <p>只收视图与动作。文本增量已经在回答正文里了；工具进度是当轮的临时状态，
 * 事后再看「执行了 3 次查询」没有价值。
 */
public final class RecordingEventSink implements AgentEventSink {

    /**
     * 留痕格式的版本号。
     *
     * <p><b>第一版就必须有。</b>以后给视图载荷加字段时，老记录里没有那些字段，
     * 前端解析出来是空值；有版本号才能明确地「不认识就当没有视图」，而不是渲染出一块坏掉的东西。
     * 事后再加版本号就得写迁移。
     */
    public static final int FORMAT_VERSION = 1;

    /**
     * 单条留痕的上限。
     *
     * <p>约束来自读取侧而不是磁盘：历史接口一次取 200 条，每条几 KB 就能拼出一个上兆的响应体，
     * 前端全量解析会卡住主线程。
     */
    public static final int MAX_TRACE_CHARS = 8 * 1024;

    private final AgentEventSink delegate;
    private final List<Map<String, Object>> views = new ArrayList<>();
    private final List<Map<String, Object>> actions = new ArrayList<>();

    public RecordingEventSink(AgentEventSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public void emit(AiEvent event) {
        // 先转发再收集：推送是用户能立刻看到的，不该等留痕。
        delegate.emit(event);
        switch (event.kind()) {
            case VIEW -> record(views, event);
            case ACTION -> record(actions, event);
            default -> {
                // 其余事件不留痕，理由见类注释。
            }
        }
    }

    private static void record(List<Map<String, Object>> into, AiEvent event) {
        synchronized (into) {
            into.add(new LinkedHashMap<>(event.data()));
        }
    }

    @Override
    public boolean cancelled() {
        return delegate.cancelled();
    }

    /** 本轮是否有值得留痕的东西。 */
    public boolean hasAnything() {
        synchronized (views) {
            synchronized (actions) {
                return !views.isEmpty() || !actions.isEmpty();
            }
        }
    }

    /**
     * 序列化成写进消息留痕字段的 JSON。
     *
     * @return 无内容时返回 {@code null}（不写空对象，省得每条消息都多一段没用的 JSON）；
     *     超限时返回一个可被识别的替代对象——**整条丢弃而不是截断**，截断的 JSON 前端解析不了，
     *     既占了空间又没用上；而完全静默地消失会被当成 bug
     */
    public String toJson(ObjectMapper mapper) {
        if (!hasAnything()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", FORMAT_VERSION);
        synchronized (views) {
            payload.put("views", List.copyOf(views));
        }
        synchronized (actions) {
            payload.put("actions", List.copyOf(actions));
        }
        String json = mapper.writeValueAsString(payload);
        if (json.length() <= MAX_TRACE_CHARS) {
            return json;
        }
        Map<String, Object> dropped = new LinkedHashMap<>();
        dropped.put("v", FORMAT_VERSION);
        dropped.put("dropped", true);
        dropped.put("reason", "oversize");
        dropped.put("views", List.of());
        dropped.put("actions", List.of());
        return mapper.writeValueAsString(dropped);
    }
}
