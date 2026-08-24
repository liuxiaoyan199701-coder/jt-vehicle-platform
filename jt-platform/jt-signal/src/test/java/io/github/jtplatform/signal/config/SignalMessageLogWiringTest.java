package io.github.jtplatform.signal.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.jtplatform.common.model.SignalPorts;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import io.github.jtplatform.signal.delivery.ProtocolPayloadMapper;
import io.github.jtplatform.signal.messagelog.DeliveringMessageLogEmitter;
import io.github.jtplatform.signal.messagelog.MessageLogEmitter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/** 报文日志采集是可选增强：关掉开关或没有投递器时必须退化成空实现，而不是启动失败或静默半装配。 */
class SignalMessageLogWiringTest {

    private static final MessagePublisher PUBLISHER =
            envelope -> PublishResult.of("test", PublishDisposition.ACCEPTED);

    @Test
    void theSwitchOffYieldsTheNoOpEmitter() {
        SignalProperties properties = new SignalProperties();
        properties.getMessageLog().setEnabled(false);

        assertSame(MessageLogEmitter.NONE, emitter(properties, PUBLISHER));
    }

    @Test
    void withoutAPublisherTheEmitterDegradesInsteadOfFailingStartup() {
        assertSame(MessageLogEmitter.NONE, emitter(new SignalProperties(), null));
    }

    @Test
    void theDefaultConfigurationCollects() {
        assertInstanceOf(DeliveringMessageLogEmitter.class,
                emitter(new SignalProperties(), PUBLISHER));
    }

    @SuppressWarnings("unchecked")
    private static MessageLogEmitter emitter(SignalProperties properties, MessagePublisher publisher) {
        ObjectProvider<MessagePublisher> publishers = Mockito.mock(ObjectProvider.class);
        Mockito.when(publishers.getIfAvailable()).thenReturn(publisher);
        SignalServerSettings settings = new SignalServerSettings(
                "signal-test", 7100, 7101, SignalPorts.forInstance(1), 7100, 7101);
        return new SignalAutoConfiguration().messageLogEmitter(
                publishers, new ProtocolPayloadMapper(),
                Clock.fixed(Instant.parse("2026-08-24T01:02:03Z"), ZoneOffset.UTC),
                settings, properties);
    }
}
