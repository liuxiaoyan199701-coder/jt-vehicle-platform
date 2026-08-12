package io.github.jtplatform.delivery.api;

import static io.github.jtplatform.delivery.TestEnvelopes.envelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.delivery.channel.AsyncChannelOptions;
import io.github.jtplatform.delivery.metrics.DeliveryMetricsSnapshot;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class ApiMessagePublisherTest {
    @TempDir
    Path temporaryDirectory;

    private int publisherSequence;

    @Test
    void slowApiNeverBlocksThePublishingThread() throws Exception {
        CompletableFuture<Void> slowResponse = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        try (ApiMessagePublisher publisher = new ApiMessagePublisher(message -> {
            entered.countDown();
            return slowResponse;
        }, options(8, 2, 2))) {
            assertTimeout(Duration.ofMillis(200), () -> assertEquals(PublishDisposition.ACCEPTED,
                    publisher.publish(envelope("device-1", 1, MessageType.LOCATION))
                            .channels().get(ApiMessagePublisher.CHANNEL_NAME)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(1, publisher.metrics().snapshot().backlog());
            slowResponse.complete(null);
            assertTrue(publisher.awaitIdle(Duration.ofSeconds(1)));
        }
    }

    @Test
    void failedCriticalMessageRetriesAtHeadBeforeFollowingMessage() throws Exception {
        CopyOnWriteArrayList<Integer> calls = new CopyOnWriteArrayList<>();
        AtomicInteger firstAttempts = new AtomicInteger();
        try (ApiMessagePublisher publisher = new ApiMessagePublisher(message -> {
            calls.add(message.serialNo());
            if (message.serialNo() == 1 && firstAttempts.incrementAndGet() <= 2) {
                return CompletableFuture.failedFuture(new IllegalStateException("unavailable"));
            }
            return CompletableFuture.completedFuture(null);
        }, options(16, 2, 4))) {
            publisher.publish(envelope("device-1", 1, MessageType.ALARM));
            publisher.publish(envelope("device-1", 2, MessageType.ALARM));

            assertTrue(publisher.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(List.of(1, 1, 1, 2), calls);
            DeliveryMetricsSnapshot metrics = publisher.metrics().snapshot();
            assertEquals(2, metrics.success());
            assertEquals(2, metrics.failure());
            assertEquals(2, metrics.retries());
            assertEquals(0, metrics.backlog());
        }
    }

    @Test
    void locationFloodIsBoundedAndCannotDrownCriticalMessages() throws Exception {
        CompletableFuture<Void> firstResponse = new CompletableFuture<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CopyOnWriteArrayList<Integer> delivered = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        try (ApiMessagePublisher publisher = new ApiMessagePublisher(message -> {
            delivered.add(message.serialNo());
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                return firstResponse;
            }
            return CompletableFuture.completedFuture(null);
        }, options(4, 1, 1))) {
            assertAccepted(publisher, 1, MessageType.LOCATION);
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            assertAccepted(publisher, 2, MessageType.LOCATION);
            assertAccepted(publisher, 3, MessageType.LOCATION);
            assertEquals(PublishDisposition.DROPPED,
                    publisher.publish(envelope("device-1", 4, MessageType.LOCATION)).channels().get("api"));
            assertAccepted(publisher, 5, MessageType.ALARM);
            assertAccepted(publisher, 6, MessageType.ALARM);

            assertEquals(4, publisher.metrics().snapshot().backlog());
            firstResponse.complete(null);
            assertTrue(publisher.awaitIdle(Duration.ofSeconds(2)));
            assertEquals(List.of(1, 2, 5, 6), delivered);
            DeliveryMetricsSnapshot metrics = publisher.metrics().snapshot();
            assertEquals(4, metrics.success());
            assertEquals(2, metrics.dropped());
            assertEquals(2L, metrics.droppedByType().get(MessageType.LOCATION));
            assertEquals(0, metrics.backlog());
        }
    }

    @Test
    void allCriticalSaturationRetainsOverflowWithoutCountingADrop() throws Exception {
        CompletableFuture<Void> response = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        try (ApiMessagePublisher publisher = new ApiMessagePublisher(message -> {
            entered.countDown();
            return response;
        }, options(2, 1, 1))) {
            assertAccepted(publisher, 1, MessageType.ALARM);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertAccepted(publisher, 2, MessageType.ALARM);
            assertEquals(PublishDisposition.ACCEPTED,
                    publisher.publish(envelope("device-1", 3, MessageType.ALARM)).channels().get("api"));
            assertEquals(3, publisher.metrics().snapshot().backlog());
            assertEquals(0, publisher.metrics().snapshot().dropped());
            assertEquals(1, publisher.metrics().snapshot().backpressured());
            response.complete(null);
            assertTrue(publisher.awaitIdle(Duration.ofSeconds(1)));
        }
    }

    @Test
    void retainedCriticalMessagesRecoverAfterPublisherRestart() throws Exception {
        Path overflow = temporaryDirectory.resolve("restart-overflow");
        AsyncChannelOptions options = options(2, 1, 1, overflow, Duration.ofMillis(20));
        CompletableFuture<Void> unavailable = new CompletableFuture<>();
        ApiMessagePublisher first = new ApiMessagePublisher(message -> unavailable, options);
        assertAccepted(first, 1, MessageType.ALARM);
        assertAccepted(first, 2, MessageType.ALARM);
        assertAccepted(first, 3, MessageType.ALARM);
        first.close();

        CopyOnWriteArrayList<Integer> recovered = new CopyOnWriteArrayList<>();
        try (ApiMessagePublisher restarted = new ApiMessagePublisher(message -> {
            recovered.add(message.serialNo());
            return CompletableFuture.completedFuture(null);
        }, options)) {
            assertTrue(restarted.awaitIdle(Duration.ofSeconds(2)));
            assertEquals(List.of(1, 2, 3), recovered);
            assertEquals(3, restarted.metrics().snapshot().success());
            assertEquals(0, restarted.metrics().snapshot().backlog());
        }
    }

    @Test
    void publishingAfterCloseCountsABestEffortDropOnlyOnce() {
        ApiMessagePublisher publisher = new ApiMessagePublisher(
                message -> CompletableFuture.completedFuture(null), options(4, 1, 1));
        publisher.close();

        assertEquals(PublishDisposition.DROPPED,
                publisher.publish(envelope("device-1", 1, MessageType.LOCATION)).channels().get("api"));
        assertEquals(1, publisher.metrics().snapshot().dropped());
    }

    @Test
    void openCircuitDropsBestEffortMessagesWithoutCallingTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AsyncChannelOptions options = new AsyncChannelOptions(8, 1, 1, 2, Duration.ofSeconds(1),
                Duration.ofMillis(5), Duration.ofMillis(20), Duration.ofSeconds(1), Duration.ofMillis(100),
                temporaryDirectory.resolve("open-circuit"));
        try (ApiMessagePublisher publisher = new ApiMessagePublisher(message -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("down"));
        }, options)) {
            for (int serial = 1; serial <= 3; serial++) {
                publisher.publish(envelope("device-1", serial, MessageType.LOCATION));
                assertTrue(publisher.awaitIdle(Duration.ofSeconds(1)));
            }

            assertEquals(2, calls.get());
            assertEquals(3, publisher.metrics().snapshot().failure());
            assertEquals(3, publisher.metrics().snapshot().dropped());
        }
    }

    @Test
    void concurrentDevicesRemainOrderedWithOneInFlightMessagePerDevice() throws Exception {
        Map<String, List<Integer>> delivered = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> active = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> maximumActive = new ConcurrentHashMap<>();
        AsyncChannelOptions options = options(512, 8, 64);
        try (ApiMessagePublisher publisher = new ApiMessagePublisher(message -> {
            int current = active.computeIfAbsent(message.deviceId(), ignored -> new AtomicInteger()).incrementAndGet();
            maximumActive.computeIfAbsent(message.deviceId(), ignored -> new AtomicInteger())
                    .accumulateAndGet(current, Math::max);
            delivered.computeIfAbsent(message.deviceId(), ignored -> new CopyOnWriteArrayList<>())
                    .add(message.serialNo());
            return CompletableFuture.runAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(2);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.get(message.deviceId()).decrementAndGet();
                }
            });
        }, options)) {
            try (var executor = Executors.newFixedThreadPool(4)) {
                List<java.util.concurrent.Callable<Void>> producers = new ArrayList<>();
                for (int device = 0; device < 4; device++) {
                    String deviceId = "device-" + device;
                    producers.add(() -> {
                        for (int serial = 0; serial < 50; serial++) {
                            assertEquals(PublishDisposition.ACCEPTED,
                                    publisher.publish(envelope(deviceId, serial, MessageType.ALARM))
                                            .channels().get("api"));
                        }
                        return null;
                    });
                }
                executor.invokeAll(producers).forEach(future -> {
                    try {
                        future.get();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
            }

            assertTrue(publisher.awaitIdle(Duration.ofSeconds(5)));
            List<Integer> expected = IntStream.range(0, 50).boxed().toList();
            for (int device = 0; device < 4; device++) {
                String deviceId = "device-" + device;
                assertEquals(expected, delivered.getOrDefault(deviceId, Collections.emptyList()));
                assertEquals(1, maximumActive.get(deviceId).get());
            }
            assertEquals(200, publisher.metrics().snapshot().success());
        }
    }

    private AsyncChannelOptions options(int capacity, int stripes, int reserve) {
        return options(capacity, stripes, reserve,
                temporaryDirectory.resolve("publisher-" + publisherSequence++), Duration.ofMillis(100));
    }

    private static AsyncChannelOptions options(
            int capacity,
            int stripes,
            int reserve,
            Path overflowDirectory,
            Duration shutdownTimeout) {
        return new AsyncChannelOptions(capacity, stripes, reserve, 100, Duration.ofMillis(50),
                Duration.ofMillis(5), Duration.ofMillis(20), Duration.ofSeconds(1), shutdownTimeout,
                overflowDirectory);
    }

    private static void assertAccepted(ApiMessagePublisher publisher, int serial, MessageType type) {
        assertEquals(PublishDisposition.ACCEPTED,
                publisher.publish(envelope("device-1", serial, type)).channels().get("api"));
    }
}
