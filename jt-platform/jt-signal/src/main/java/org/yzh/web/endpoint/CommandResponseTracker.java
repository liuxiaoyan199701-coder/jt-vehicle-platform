package org.yzh.web.endpoint;

import io.github.yezhihao.netmc.core.model.Message;
import io.github.yezhihao.netmc.session.Session;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yzh.protocol.t808.T0001;
import reactor.core.publisher.MonoSink;

/**
 * 追踪「期望特定应答类」的下行指令，让终端的 T0001 通用应答能够快速失败等待中的请求。
 *
 * <p>netmc 的应答关联按「应答类名 + 应答流水号」匹配（{@code Session.response}）。
 * 终端对不支持的指令回 T0001（如 resultCode=3 不支持），而网关为 0x8801 等指令等待的是
 * T0805 这类专用应答——T0001 匹配不到任何等待者，被静默丢弃，等待方只能干等 10 秒超时。
 *
 * <p>本组件利用 netmc 的 {@code responseInterceptor}（每条入站消息都会先经过它），
 * 在 T0001 到达时找到同会话、同应答流水号的等待记录，立刻以明确的失败完成该等待。
 *
 * <p>设计为独立组件而不是放进 MessageManager：JTSessionListener 需要注册拦截器，
 * 而它又被 SessionManager 依赖，直接依赖 MessageManager 会形成
 * JTSessionListener → MessageManager → SessionManager → JTSessionListener 的循环。
 */
@Component
public class CommandResponseTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandResponseTracker.class);

    private final ConcurrentMap<Session, ConcurrentMap<Integer, MonoSink<Integer>>> pending =
            new ConcurrentHashMap<>();

    /**
     * 把会话的入站消息挂到本组件。在会话创建时调用。
     */
    public void attach(Session session) {
        session.responseInterceptor(this::onResponse);
    }

    /**
     * 登记一条等待记录。
     *
     * @param serialNo 由 netmc 的 requestInterceptor 分配的下行流水号
     */
    void register(Session session, int serialNo, MonoSink<Integer> sink) {
        pending.computeIfAbsent(session, ignored -> new ConcurrentHashMap<>()).put(serialNo, sink);
    }

    void unregister(Session session, int serialNo) {
        ConcurrentMap<Integer, MonoSink<Integer>> sessionPending = pending.get(session);
        if (sessionPending != null) {
            sessionPending.remove(serialNo);
        }
    }

    /** 会话销毁时清理，避免对已断开会话的引用残留 */
    public void clear(Session session) {
        pending.remove(session);
    }

    private void onResponse(Session session, Message message) {
        if (!(message instanceof T0001 reply)) {
            return;
        }
        // resultCode=0（成功）不做快速失败：终端可能随后还会发专用应答，贸然完成等待
        // 会丢掉真正的应答；让等待方继续等到超时更安全。
        if (reply.getResultCode() == T0001.Success) {
            return;
        }
        ConcurrentMap<Integer, MonoSink<Integer>> sessionPending = pending.get(session);
        if (sessionPending == null) {
            return;
        }
        MonoSink<Integer> sink = sessionPending.remove(reply.getResponseSerialNo());
        if (sink != null) {
            // Reactor 对已取消的 sink 调用 success 是安全空操作
            LOGGER.info("Command {} rejected by terminal with resultCode={}",
                    reply.getResponseMessageId(), reply.getResultCode());
            sink.success(reply.getResultCode());
        }
    }
}
