package io.github.jtplatform.signal.config;

import io.github.jtplatform.common.port.DeviceRouter;
import io.github.jtplatform.delivery.config.DeliveryAutoConfiguration;
import io.github.jtplatform.delivery.listener.MessageEnvelopeListener;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.signal.delivery.MessageTypeClassifier;
import io.github.jtplatform.signal.delivery.ProtocolPayloadMapper;
import io.github.jtplatform.signal.delivery.SignalMessageDispatcher;
import io.github.jtplatform.signal.delivery.SignalMessageEnvelopeMapper;
import io.github.jtplatform.signal.diagnostics.ConnectionEventEmitter;
import io.github.jtplatform.signal.messagelog.DeliveringMessageLogEmitter;
import io.github.jtplatform.signal.messagelog.MessageLogEmitter;
import io.github.jtplatform.signal.protocol.SignalMultiPacketDecoder;
import io.github.jtplatform.signal.command.SignalStreamCommandController;
import io.github.jtplatform.signal.command.SignalStreamCommandHandler;
import io.github.jtplatform.signal.session.RegistrationTokenStore;
import io.github.yezhihao.netmc.NettyConfig;
import io.github.yezhihao.netmc.Server;
import io.github.yezhihao.netmc.codec.Delimiter;
import io.github.yezhihao.netmc.core.HandlerMapping;
import io.github.yezhihao.netmc.session.SessionManager;
import io.github.yezhihao.protostar.SchemaManager;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.yzh.protocol.codec.JTMessageAdapter;
import org.yzh.protocol.codec.JTMessageEncoder;
import org.yzh.web.endpoint.CommandResponseTracker;
import org.yzh.web.endpoint.JTHandlerInterceptor;
import org.yzh.web.endpoint.JTMessagePushAdapter;
import org.yzh.web.endpoint.JTMultiPacketListener;
import org.yzh.web.endpoint.JTSessionListener;
import org.yzh.web.endpoint.MessageManager;
import org.yzh.web.model.enums.SessionKey;

@AutoConfiguration
@AutoConfigureAfter(DeliveryAutoConfiguration.class)
@EnableConfigurationProperties(SignalProperties.class)
@ConditionalOnProperty(prefix = "jt.signal", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "org.yzh.web")
@Import({SignalStreamCommandController.class,
        io.github.jtplatform.signal.command.RecordingUploadCommandController.class,
        io.github.jtplatform.signal.admin.DeviceSessionAdminController.class})
public class SignalAutoConfiguration implements EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Bean
    SignalServerSettings signalServerSettings(SignalProperties properties) {
        int instanceNumber = environment.getProperty("jt.instance.number", Integer.class, 1);
        boolean cluster = environment.acceptsProfiles(Profiles.of("cluster"));
        return SignalPortResolver.resolve(properties, instanceNumber, cluster);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock signalClock() {
        return Clock.systemUTC();
    }

    @Bean
    SchemaManager signalSchemaManager(SignalProperties properties) {
        return new SchemaManager(properties.getMessagePackages());
    }

    @Bean
    JTMultiPacketListener signalMultiPacketListener() {
        return new JTMultiPacketListener(10);
    }

    @Bean
    SignalMultiPacketDecoder signalMultiPacketDecoder(
            SchemaManager signalSchemaManager,
            JTMultiPacketListener listener) {
        return new SignalMultiPacketDecoder(signalSchemaManager, listener);
    }

    @Bean
    JTMessageEncoder signalMessageEncoder(SchemaManager signalSchemaManager) {
        return new JTMessageEncoder(signalSchemaManager);
    }

    @Bean
    ProtocolPayloadMapper protocolPayloadMapper() {
        return new ProtocolPayloadMapper();
    }

    @Bean
    MessageTypeClassifier messageTypeClassifier() {
        return new MessageTypeClassifier();
    }

    @Bean
    SignalMessageEnvelopeMapper signalMessageEnvelopeMapper(
            ProtocolPayloadMapper payloadMapper,
            MessageTypeClassifier classifier,
            Clock clock,
            SignalServerSettings settings) {
        return new SignalMessageEnvelopeMapper(payloadMapper, classifier, clock, settings.instanceId());
    }

    @Bean
    SignalMessageDispatcher signalMessageDispatcher(
            SignalMessageEnvelopeMapper mapper,
            MessagePublisher publisher,
            ObjectProvider<MessageEnvelopeListener> listenerProvider) {
        return new SignalMessageDispatcher(
                mapper, publisher, listenerProvider.orderedStream().toList());
    }

    @Bean
    JTMessageAdapter signalMessageAdapter(
            JTMessageEncoder signalMessageEncoder,
            SignalMultiPacketDecoder signalMultiPacketDecoder,
            SignalMessageDispatcher dispatcher,
            MessageLogEmitter messageLogEmitter) {
        return new JTMessagePushAdapter(
                signalMessageEncoder, signalMultiPacketDecoder, dispatcher, messageLogEmitter);
    }

    /**
     * 没装配投递器、或配置关掉采集时退化成空实现：报文日志是可选增强，不能成为收发报文的前提。
     */
    @Bean
    MessageLogEmitter messageLogEmitter(
            ObjectProvider<MessagePublisher> publishers,
            ProtocolPayloadMapper protocolPayloadMapper,
            Clock clock,
            SignalServerSettings settings,
            SignalProperties properties) {
        SignalProperties.MessageLog config = properties.getMessageLog();
        MessagePublisher publisher = publishers.getIfAvailable();
        if (publisher == null || !config.isEnabled()) {
            return MessageLogEmitter.NONE;
        }
        return new DeliveringMessageLogEmitter(publisher, protocolPayloadMapper, clock,
                settings.instanceId(), config.getMaxHexChars(), config.getMaxJsonChars());
    }

    @Bean
    JTHandlerInterceptor signalHandlerInterceptor(ConnectionEventEmitter diagnostics) {
        return new JTHandlerInterceptor(diagnostics);
    }

    @Bean
    RegistrationTokenStore registrationTokenStore() {
        return new RegistrationTokenStore();
    }

    @Bean
    ConnectionEventEmitter connectionEventEmitter(
            MessagePublisher publisher, Clock clock, SignalServerSettings settings) {
        return new ConnectionEventEmitter(publisher, clock, settings.instanceId());
    }

    @Bean
    JTSessionListener signalSessionListener(
            DeviceRouter deviceRouter, SignalServerSettings settings,
            CommandResponseTracker rejectionTracker, ConnectionEventEmitter diagnostics) {
        return new JTSessionListener(deviceRouter, settings.instanceId(), rejectionTracker, diagnostics);
    }

    @Bean
    SessionManager signalSessionManager(JTSessionListener listener) {
        return new SessionManager(SessionKey.class, listener);
    }

    @Bean
    @Primary
    SignalStreamCommandHandler signalStreamCommandHandler(MessageManager messageManager, Clock clock) {
        return new SignalStreamCommandHandler(messageManager, clock);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @Primary
    Server signalTcpServer(
            JTMessageAdapter signalMessageAdapter,
            HandlerMapping handlerMapping,
            JTHandlerInterceptor interceptor,
            SessionManager sessionManager,
            SignalServerSettings settings,
            SignalProperties properties) {
        return serverBuilder(signalMessageAdapter, handlerMapping, interceptor, sessionManager, properties)
                .setPort(settings.listenTcpPort())
                .setMaxFrameLength(2 + 21 + 1023 * 2 + 1 + 2)
                .setName("JT808-TCP-" + settings.instanceId())
                .build();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    Server signalUdpServer(
            JTMessageAdapter signalMessageAdapter,
            HandlerMapping handlerMapping,
            JTHandlerInterceptor interceptor,
            SessionManager sessionManager,
            SignalServerSettings settings,
            SignalProperties properties) {
        return serverBuilder(signalMessageAdapter, handlerMapping, interceptor, sessionManager, properties)
                .setPort(settings.listenUdpPort())
                .setMaxFrameLength(2 + 21 + 1023 * 2 + 1 + 2)
                .setEnableUDP(true)
                .setName("JT808-UDP-" + settings.instanceId())
                .build();
    }

    private static NettyConfig.Builder serverBuilder(
            JTMessageAdapter adapter,
            HandlerMapping handlerMapping,
            JTHandlerInterceptor interceptor,
            SessionManager sessionManager,
            SignalProperties properties) {
        Duration idleTimeout = properties.getIdleTimeout();
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("jt.signal.idle-timeout must be positive");
        }
        long idleSeconds = Math.max(1, idleTimeout.toSeconds());
        return NettyConfig.custom()
                .setIdleStateTime(Math.toIntExact(idleSeconds), 0, 0)
                .setDelimiters(new Delimiter(new byte[] {0x7e}, false))
                .setDecoder(adapter)
                .setEncoder(adapter)
                .setHandlerMapping(handlerMapping)
                .setHandlerInterceptor(interceptor)
                .setSessionManager(sessionManager);
    }
}
