package org.yzh.web.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.jtplatform.common.port.StreamCommandException;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import io.github.jtplatform.signal.diagnostics.ConnectionEventEmitter;
import io.github.yezhihao.netmc.core.model.Message;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yzh.protocol.t808.T0001;
import org.yzh.protocol.t808.T0805;
import org.yzh.protocol.t808.T8801;

/**
 * 指令结局事件：四种结局各产生一条事件，且调用方看到的返回值与异常语义不因埋点而变。
 */
class MessageManagerTest {
    private final CapturingPublisher publisher = new CapturingPublisher();
    private final ConnectionEventEmitter diagnostics = new ConnectionEventEmitter(
            publisher, Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC), "signal-1");

    @Test
    void offlineDeviceEmitsOfflineOutcomeAndStillFailsAsOffline() {
        MessageManager manager = new MessageManager(
                new SessionManager(), new CommandResponseTracker(), diagnostics);

        AtomicReference<Throwable> error = subscribe(
                manager.request("device-1", new T8801(), T0805.class));

        assertThat(error.get()).isInstanceOf(StreamCommandException.class)
                .hasMessageContaining("Device is offline");
        assertThat(detail()).containsEntry("commandMsgId", "0x8801")
                .containsEntry("outcome", "OFFLINE");
    }

    @Test
    void generalReplyRejectionEmitsRejectedAndKeepsTheRejectionError() {
        Session session = mock(Session.class);
        CommandResponseTracker tracker = new CommandResponseTracker();
        tracker.attach(session);
        BiConsumer<Session, Message> inbound = captureResponseInterceptor(session);
        doReturn(reactor.core.publisher.Mono.never()).when(session).request(any(), any());
        MessageManager manager = manager(session, tracker, Duration.ofSeconds(10));

        AtomicReference<Throwable> error = subscribe(
                manager.request("device-1", new T8801(), T0805.class));
        inbound.accept(session, generalReply(T0001.NotSupport));

        assertThat(error.get()).isInstanceOf(StreamCommandException.class)
                .hasMessageContaining("unsupported");
        assertThat(detail()).containsEntry("commandMsgId", "0x8801")
                .containsEntry("outcome", "REJECTED").containsEntry("resultCode", 3);
    }

    @Test
    void missingResponseEmitsTimeoutAndKeepsTheTimeoutError() {
        Session session = mock(Session.class);
        doReturn(reactor.core.publisher.Mono.never()).when(session).request(any(), any());
        MessageManager manager = manager(session, new CommandResponseTracker(), Duration.ofMillis(50));

        AtomicReference<Throwable> error = subscribe(
                manager.request("device-1", new T8801(), T0805.class));

        await(() -> error.get() != null);
        assertThat(error.get()).isInstanceOf(StreamCommandException.class)
                .hasMessageContaining("timed out");
        assertThat(detail()).containsEntry("commandMsgId", "0x8801")
                .containsEntry("outcome", "TIMEOUT");
    }

    @Test
    void specializedResponseEmitsOkAndReturnsTheResponseUnchanged() {
        Session session = mock(Session.class);
        T0805 response = new T0805();
        doReturn(reactor.core.publisher.Mono.just(response)).when(session).request(any(), any());
        MessageManager manager = manager(session, new CommandResponseTracker(), Duration.ofSeconds(10));

        assertThat(manager.request("device-1", new T8801(), T0805.class).block()).isSameAs(response);
        assertThat(detail()).containsEntry("commandMsgId", "0x8801").containsEntry("outcome", "OK");
    }

    /** 期望应答就是 T0001 的指令（文本下发、云台控制），结果码非 0 是拒绝而不是成功。 */
    @Test
    void generalReplyAsTheExpectedResponseClassifiesNonZeroResultAsRejected() {
        Session session = mock(Session.class);
        T0001 reply = generalReply(T0001.MessageError);
        doReturn(reactor.core.publisher.Mono.just(reply)).when(session).request(any(), any());
        MessageManager manager = manager(session, new CommandResponseTracker(), Duration.ofSeconds(10));

        assertThat(manager.request("device-1", new T8801(), T0001.class).block()).isSameAs(reply);
        assertThat(detail()).containsEntry("outcome", "REJECTED").containsEntry("resultCode", 2);
    }

    @Test
    void sendFailureEmitsFailedRatherThanTimeout() {
        Session session = mock(Session.class);
        doReturn(reactor.core.publisher.Mono.error(new IllegalStateException("channel closed")))
                .when(session).request(any(), any());
        MessageManager manager = manager(session, new CommandResponseTracker(), Duration.ofSeconds(10));

        AtomicReference<Throwable> error = subscribe(
                manager.request("device-1", new T8801(), T0805.class));

        assertThat(error.get()).isInstanceOf(StreamCommandException.class)
                .hasMessageContaining("Failed to send command");
        assertThat(detail()).containsEntry("outcome", "FAILED");
    }

    private MessageManager manager(Session session, CommandResponseTracker tracker, Duration timeout) {
        return new MessageManager(new SessionManager(), tracker, diagnostics, timeout) {
            @Override
            Session findSession(String identity) {
                return session;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail() {
        assertThat(publisher.events).hasSize(1);
        MessageEnvelope event = publisher.events.getFirst();
        assertThat(event.payload()).containsEntry("kind", "COMMAND_RESULT");
        return (Map<String, Object>) event.payload().get("detail");
    }

    private static T0001 generalReply(int resultCode) {
        T0001 reply = new T0001();
        reply.setResponseSerialNo(0);
        reply.setResponseMessageId(0x8801);
        reply.setResultCode(resultCode);
        return reply;
    }

    private static AtomicReference<Throwable> subscribe(reactor.core.publisher.Mono<?> mono) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        mono.subscribe(ignored -> { }, error::set);
        return error;
    }

    @SuppressWarnings("unchecked")
    private static BiConsumer<Session, Message> captureResponseInterceptor(Session session) {
        ArgumentCaptor<BiConsumer<Session, Message>> captor = ArgumentCaptor.forClass(BiConsumer.class);
        verify(session).responseInterceptor(captor.capture());
        return captor.getValue();
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<MessageEnvelope> events = new ArrayList<>();

        @Override
        public synchronized PublishResult publish(MessageEnvelope envelope) {
            events.add(envelope);
            return PublishResult.of("test", PublishDisposition.ACCEPTED);
        }
    }
}
