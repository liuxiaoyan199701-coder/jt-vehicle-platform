package io.github.jtconsole.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 留痕收集：收什么、不收什么、体积超了怎么办。 */
class RecordingEventSinkTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final class Capturing implements AgentEventSink {
        private final List<AiEvent> forwarded = new ArrayList<>();
        private boolean cancelled;

        @Override
        public void emit(AiEvent event) {
            forwarded.add(event);
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }
    }

    @Test
    void forwardsEverythingButOnlyRecordsViewsAndActions() {
        Capturing downstream = new Capturing();
        RecordingEventSink sink = new RecordingEventSink(downstream);

        sink.emit(AiEvent.delta("一段文字"));
        sink.emit(AiEvent.toolStart("query_track", "查询轨迹"));
        sink.emit(new AiEvent(AiEvent.Kind.VIEW, Map.of("viewId", "v_1", "type", "live_map")));
        sink.emit(new AiEvent(AiEvent.Kind.ACTION, Map.of("proposalId", "p_1", "type", "vehicle_create")));
        sink.emit(AiEvent.done("stop"));

        // 转发是全量的——留痕不该改变用户当场看到的内容。
        assertThat(downstream.forwarded).hasSize(5);

        String json = sink.toJson(mapper);
        assertThat(json).contains("\"v\":1").contains("v_1").contains("p_1");
        // 文本增量已经在回答正文里，工具进度是当轮的临时状态，都不留痕。
        assertThat(json).doesNotContain("一段文字").doesNotContain("query_track");
    }

    /** 没东西可留时返回空，免得每条消息都多存一段没用的 JSON。 */
    @Test
    void recordsNothingWhenNoViewsOrActionsOccurred() {
        RecordingEventSink sink = new RecordingEventSink(new Capturing());

        sink.emit(AiEvent.delta("只有文字"));

        assertThat(sink.hasAnything()).isFalse();
        assertThat(sink.toJson(mapper)).isNull();
    }

    /**
     * 超限整条丢弃并留下标记。
     *
     * <p>不截断：截断的 JSON 前端解析不了，既占空间又没用上。而完全静默地消失会被当成 bug——
     * 所以要留一个前端能识别、能显示「本轮内容因体积过大未保存」的对象。
     */
    @Test
    void replacesAnOversizedTraceWithAnIdentifiableMarker() {
        RecordingEventSink sink = new RecordingEventSink(new Capturing());
        String bulky = "x".repeat(RecordingEventSink.MAX_TRACE_CHARS);
        sink.emit(new AiEvent(AiEvent.Kind.VIEW, Map.of("viewId", "v_big", "blob", bulky)));

        String json = sink.toJson(mapper);

        assertThat(json.length()).isLessThan(RecordingEventSink.MAX_TRACE_CHARS);
        assertThat(json).contains("\"dropped\":true").contains("oversize").contains("\"v\":1");
        assertThat(json).doesNotContain("v_big");
    }

    /** 客户端断开后仍然收集：用户下次打开这段会话，仍该看到当时的内容。 */
    @Test
    void keepsRecordingAfterTheClientDisconnects() {
        Capturing downstream = new Capturing();
        downstream.cancelled = true;
        RecordingEventSink sink = new RecordingEventSink(downstream);

        sink.emit(new AiEvent(AiEvent.Kind.VIEW, Map.of("viewId", "v_1")));

        assertThat(sink.cancelled()).isTrue();
        assertThat(sink.toJson(mapper)).contains("v_1");
    }
}
