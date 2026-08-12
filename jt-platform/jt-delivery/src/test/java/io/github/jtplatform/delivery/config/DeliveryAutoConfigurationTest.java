package io.github.jtplatform.delivery.config;

import static io.github.jtplatform.delivery.TestEnvelopes.envelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.delivery.api.ApiPushTransport;
import io.github.jtplatform.delivery.metrics.DeliveryMetricsRegistry;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.delivery.publisher.CompositeMessagePublisher;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.NoOpMessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.rocketmq.RocketMqQueue;
import io.github.jtplatform.delivery.rocketmq.RocketMqTransport;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeliveryAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeliveryAutoConfiguration.class));

    @Test
    void defaultConfigurationStartsAsNoOpWithoutExternalTransports() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            MessagePublisher publisher = context.getBean(MessagePublisher.class);
            assertInstanceOf(NoOpMessagePublisher.class, publisher);
            assertEquals(PublishDisposition.DISABLED,
                    publisher.publish(envelope("device-1", 1, MessageType.LOCATION)).channels().get("none"));
            assertTrue(context.getBean(DeliveryMetricsRegistry.class).snapshots().isEmpty());
            DeliveryProperties properties = context.getBean(DeliveryProperties.class);
            assertTrue(properties.getChannels().isEmpty());
        });
    }

    @Test
    void bothChannelsReceiveTheSameImmutableEnvelope() {
        CopyOnWriteArrayList<MessageEnvelope> apiMessages = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<MessageEnvelope> mqMessages = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(2);
        ApiPushTransport api = message -> {
            apiMessages.add(message);
            delivered.countDown();
            return CompletableFuture.completedFuture(null);
        };
        RocketMqTransport mq = new RocketMqTransport() {
            @Override
            public List<RocketMqQueue> availableQueues(String topic) {
                return List.of(new RocketMqQueue("broker-a", 0));
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> send(
                    String topic, RocketMqQueue queue, String messageKey, MessageEnvelope message) {
                mqMessages.add(message);
                delivered.countDown();
                return CompletableFuture.completedFuture(null);
            }
        };

        runner.withBean(ApiPushTransport.class, () -> api)
                .withBean(RocketMqTransport.class, () -> mq)
                .withPropertyValues("jt.delivery.channels[0]=api", "jt.delivery.channels[1]=rocket-mq")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    MessagePublisher publisher = context.getBean(MessagePublisher.class);
                    assertInstanceOf(CompositeMessagePublisher.class, publisher);
                    MessageEnvelope envelope = envelope("device-1", 1, MessageType.ALARM);
                    assertTrue(publisher.publish(envelope).acceptedByEveryEnabledChannel());
                    assertTrue(delivered.await(2, TimeUnit.SECONDS));
                    assertEquals(List.of(envelope), apiMessages);
                    assertEquals(List.of(envelope), mqMessages);
                    assertEquals(java.util.Set.of("api", "rocketmq"),
                            context.getBean(DeliveryMetricsRegistry.class).snapshots().keySet());
                });
    }

    @Test
    void apiCanBeEnabledIndependently() {
        CountDownLatch apiDelivered = new CountDownLatch(1);
        runner.withBean(ApiPushTransport.class, () -> message -> {
                    apiDelivered.countDown();
                    return CompletableFuture.completedFuture(null);
                })
                .withPropertyValues("jt.delivery.channels=api", "jt.delivery.api.queue-capacity=4")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    PublishDisposition disposition = context.getBean(MessagePublisher.class)
                            .publish(envelope("device-1", 1, MessageType.ALARM)).channels().get("api");
                    assertEquals(PublishDisposition.ACCEPTED, disposition);
                    assertTrue(apiDelivered.await(2, TimeUnit.SECONDS));
                    assertEquals(java.util.Set.of("api"),
                            context.getBean(DeliveryMetricsRegistry.class).snapshots().keySet());
                });
    }

    @Test
    void mqOnlyNeverInvokesApiTransport() {
        java.util.concurrent.atomic.AtomicInteger apiCalls = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch mqDelivered = new CountDownLatch(1);
        runner.withBean(ApiPushTransport.class, () -> message -> {
                    apiCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                })
                .withBean(RocketMqTransport.class, () -> new RocketMqTransport() {
                    @Override
                    public List<RocketMqQueue> availableQueues(String topic) {
                        return List.of(new RocketMqQueue("broker-a", 0));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<Void> send(
                            String topic, RocketMqQueue queue, String messageKey, MessageEnvelope message) {
                        mqDelivered.countDown();
                        return CompletableFuture.completedFuture(null);
                    }
                })
                .withPropertyValues("jt.delivery.channels=rocket-mq")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    context.getBean(MessagePublisher.class).publish(envelope("device-1", 1, MessageType.ALARM));
                    assertTrue(mqDelivered.await(2, TimeUnit.SECONDS));
                    assertEquals(0, apiCalls.get());
                });
    }

    @Test
    void enabledRocketMqProvidesALazyBuiltInClientAdapter() {
        runner.withPropertyValues("jt.delivery.channels=rocket-mq").run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(io.github.jtplatform.delivery.rocketmq.RocketMqMessagePublisher.class,
                    context.getBean(MessagePublisher.class));
        });
    }
}
