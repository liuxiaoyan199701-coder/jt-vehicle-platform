package io.github.jtconsole.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.ai.agent.AiEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * 事件推送的并发安全。
 *
 * <p>这条链路上有两个写入方：跑模型的工作线程推文本增量，框架执行工具回调的线程推工具进度、
 * 动作提议与视图提议。底层的 SSE 发送不是线程安全的，两个线程同时写会让字节交错，
 * 前端按空行分帧就会解出半个 JSON。
 */
class SseSinkTest {

    /**
     * 用一个「进入时记 start、离开前记 end」的假发送端，把交错变成可观测的事件。
     *
     * <p>没有互斥时，两个线程的 start 会连着出现；有互斥时日志必然严格 start-end 交替。
     * 中间那点停顿是为了把竞争窗口撑开——否则线程可能快到从不重叠，测试就成了摆设。
     */
    private static final class InterleavingDetector extends SseEmitter {

        private final List<String> log = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            log.add("start");
            try {
                Thread.sleep(2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            log.add("end");
        }
    }

    @Test
    void serializesConcurrentEmitsSoFramesNeverInterleave() throws Exception {
        InterleavingDetector emitter = new InterleavingDetector();
        AiChatController.SseSink sink =
                new AiChatController.SseSink(emitter, new AtomicBoolean(false), new ObjectMapper());

        int writers = 6;
        int perWriter = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        for (int writer = 0; writer < writers; writer++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        sink.emit(AiEvent.delta("片段"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        List<String> log = List.copyOf(emitter.log);
        assertThat(log).hasSize(writers * perWriter * 2);
        for (int i = 0; i < log.size(); i += 2) {
            assertThat(log.get(i)).as("第 %d 次发送应当独占，实际日志：%s", i / 2, log).isEqualTo("start");
            assertThat(log.get(i + 1)).isEqualTo("end");
        }
    }

    /** 一次写失败即认定客户端已断开，后续事件静默丢弃——继续写只会不断抛异常刷日志。 */
    @Test
    void stopsEmittingAfterTheClientDisconnects() {
        SseEmitter broken = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("客户端已断开");
            }
        };
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AiChatController.SseSink sink =
                new AiChatController.SseSink(broken, cancelled, new ObjectMapper());

        sink.emit(AiEvent.delta("第一段"));

        assertThat(cancelled).isTrue();
        assertThat(sink.cancelled()).isTrue();
        // 已取消后再推不应抛异常。
        sink.emit(AiEvent.delta("第二段"));
    }
}
