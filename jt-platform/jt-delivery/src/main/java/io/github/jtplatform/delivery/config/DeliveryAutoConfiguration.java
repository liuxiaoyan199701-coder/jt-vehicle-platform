package io.github.jtplatform.delivery.config;

import io.github.jtplatform.delivery.api.ApiMessagePublisher;
import io.github.jtplatform.delivery.api.ApiPushTransport;
import io.github.jtplatform.delivery.api.HttpApiPushTransport;
import io.github.jtplatform.delivery.codec.JacksonMessageEnvelopeEncoder;
import io.github.jtplatform.delivery.codec.MessageEnvelopeEncoder;
import io.github.jtplatform.delivery.metrics.DeliveryMetricsRegistry;
import io.github.jtplatform.delivery.publisher.CompositeMessagePublisher;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.NoOpMessagePublisher;
import io.github.jtplatform.delivery.rocketmq.DeviceQueueSelector;
import io.github.jtplatform.delivery.rocketmq.ApacheRocketMqTransport;
import io.github.jtplatform.delivery.rocketmq.RocketMqMessagePublisher;
import io.github.jtplatform.delivery.rocketmq.RocketMqTransport;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryAutoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    DeliveryMetricsRegistry deliveryMetricsRegistry() {
        return new DeliveryMetricsRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    MessageEnvelopeEncoder messageEnvelopeEncoder() {
        return new JacksonMessageEnvelopeEncoder();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(MessagePublisher.class)
    MessagePublisher messagePublisher(
            DeliveryProperties properties,
            DeliveryMetricsRegistry metricsRegistry,
            MessageEnvelopeEncoder encoder,
            ObjectProvider<ApiPushTransport> apiTransportProvider,
            ObjectProvider<RocketMqTransport> rocketMqTransportProvider) {
        ApiPushTransport apiTransport = null;
        RocketMqTransport rocketMqTransport = null;
        boolean closeRocketMqTransport = false;
        if (properties.isEnabled(DeliveryChannelKind.API)) {
            apiTransport = apiTransportProvider.getIfAvailable();
            if (apiTransport == null) {
                apiTransport = defaultApiTransport(properties.getApi(), encoder);
            }
        }
        if (properties.isEnabled(DeliveryChannelKind.ROCKET_MQ)) {
            rocketMqTransport = rocketMqTransportProvider.getIfAvailable();
            if (rocketMqTransport == null) {
                var rocketMq = properties.getRocketMq();
                rocketMqTransport = new ApacheRocketMqTransport(
                        rocketMq.getNameServer(),
                        rocketMq.getProducerGroup(),
                        rocketMq.getNamespace(),
                        rocketMq.getSendTimeout(),
                        encoder);
                closeRocketMqTransport = true;
            }
        }

        List<MessagePublisher> channels = new ArrayList<>(2);
        if (apiTransport != null) {
            ApiMessagePublisher publisher = new ApiMessagePublisher(apiTransport, properties.getApi().toOptions());
            metricsRegistry.register(publisher.metrics());
            channels.add(publisher);
        }
        if (rocketMqTransport != null) {
            RocketMqMessagePublisher publisher = new RocketMqMessagePublisher(properties.getRocketMq().getTopic(),
                    rocketMqTransport, new DeviceQueueSelector(), properties.getRocketMq().toOptions(),
                    closeRocketMqTransport);
            metricsRegistry.register(publisher.metrics());
            channels.add(publisher);
        }
        if (channels.isEmpty()) {
            LOGGER.info("All delivery channels are disabled; parsed messages will not be delivered");
            return new NoOpMessagePublisher();
        }
        LOGGER.info("Enabled delivery channels: {}", properties.getChannels());
        return channels.size() == 1 ? channels.getFirst() : new CompositeMessagePublisher(channels);
    }

    private static ApiPushTransport defaultApiTransport(
            DeliveryProperties.Api properties,
            MessageEnvelopeEncoder encoder) {
        if (properties.getEndpoint() == null) {
            throw new IllegalStateException("API delivery is enabled but neither an endpoint nor ApiPushTransport bean exists");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        return new HttpApiPushTransport(client, properties.getEndpoint(), properties.getSendTimeout(), encoder,
                properties.getHeaders());
    }
}
