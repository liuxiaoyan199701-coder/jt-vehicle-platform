package io.github.jtplatform.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.model.StreamTicket;
import io.github.jtplatform.common.port.InMemoryMediaInstanceRegistry;
import io.github.jtplatform.common.port.InMemoryStreamRegistry;
import io.github.jtplatform.common.port.StreamCommandPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final StreamKey KEY = new StreamKey("device-1", 1, StreamKind.MAIN);

    @Test
    void concurrentSubscribersRegisterAndCommandOnlyOnce() throws Exception {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        InMemoryMediaInstanceRegistry instances = instances();
        CountingCommands commands = new CountingCommands();
        try (var schedulerExecutor = Executors.newScheduledThreadPool(2);
             var callers = Executors.newFixedThreadPool(12)) {
            StreamCoordinator coordinator = coordinator(streams, instances, commands, schedulerExecutor,
                    Duration.ofSeconds(5), Duration.ofSeconds(5));

            java.util.concurrent.Callable<StreamTicket> open = () -> coordinator.open(KEY);
            var futures = callers.invokeAll(Collections.nCopies(100, open));
            for (var future : futures) {
                assertEquals("stream-1", future.get().streamId());
            }

            assertEquals(1, commands.openCount.get());
            assertEquals(100, streams.find(KEY).orElseThrow().subscriberCount());
            coordinator.onFirstPacket(KEY, "media-1");
        }
    }

    @Test
    void firstPacketMustBelongToAssignedInstance() {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        CountingCommands commands = new CountingCommands();
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = coordinator(streams, instances(), commands, executor,
                    Duration.ofSeconds(5), Duration.ofSeconds(5));
            coordinator.open(KEY);
            assertFalse(coordinator.onFirstPacket(KEY, "media-2"));
            assertTrue(coordinator.onFirstPacket(KEY, "media-1"));
            assertEquals(StreamState.LIVE, streams.find(KEY).orElseThrow().state());
        }
    }

    @Test
    void zeroSubscribersCloseAfterDelayAndResubscriptionCancelsClose() throws Exception {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        CountingCommands commands = new CountingCommands();
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = coordinator(streams, instances(), commands, executor,
                    Duration.ofMillis(80), Duration.ofSeconds(5));
            coordinator.open(KEY);
            coordinator.onFirstPacket(KEY, "media-1");
            assertEquals(0, coordinator.release(KEY));
            Thread.sleep(20);
            coordinator.open(KEY);
            Thread.sleep(100);
            assertEquals(0, commands.closeCount.get());
            coordinator.release(KEY);
            assertTrue(await(() -> commands.closeCount.get() == 1, 500));
            assertEquals(StreamState.DEAD, streams.find(KEY).orElseThrow().state());
        }
    }

    @Test
    void pendingStreamExpiresWithExplicitReason() throws Exception {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = coordinator(streams, instances(), new CountingCommands(), executor,
                    Duration.ofSeconds(5), Duration.ofMillis(40));
            coordinator.open(KEY);
            var waitingSubscriber = coordinator.awaitLive(KEY);
            assertTrue(await(() -> streams.find(KEY).orElseThrow().state() == StreamState.DEAD, 500));
            assertEquals("DEVICE_NO_RESPONSE", streams.find(KEY).orElseThrow().terminalReason());
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.CompletionException.class, waitingSubscriber::join);
            assertTrue(failure.getCause().getMessage().contains("DEVICE_NO_RESPONSE"));
        }
    }

    @Test
    void pendingExpiryReportsTheWaitingMediaNodeAndTheRealWaitedTime() throws Exception {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        MutableClock clock = new MutableClock();
        List<String> notified = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = coordinator(streams, instances(), new CountingCommands(),
                    executor, Duration.ofSeconds(5), Duration.ofMillis(200), clock,
                    (streamKey, mediaInstanceId, waitedMillis) ->
                            notified.add(streamKey.externalId() + '|' + mediaInstanceId + '|' + waitedMillis));

            coordinator.open(KEY);
            clock.advance(Duration.ofSeconds(3));

            assertTrue(await(() -> !notified.isEmpty(), 1_000));
            assertEquals(KEY.externalId() + "|media-1|3000", notified.getFirst());
        }
    }

    @Test
    void streamThatGoesLiveIsNeverReportedAsNotArrived() throws Exception {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        List<String> notified = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = coordinator(streams, instances(), new CountingCommands(),
                    executor, Duration.ofSeconds(5), Duration.ofMillis(60), CLOCK,
                    (streamKey, mediaInstanceId, waitedMillis) -> notified.add(streamKey.externalId()));

            coordinator.open(KEY);
            assertTrue(coordinator.onFirstPacket(KEY, "media-1"));
            Thread.sleep(200);

            assertTrue(notified.isEmpty());
            assertEquals(StreamState.LIVE, streams.find(KEY).orElseThrow().state());
        }
    }

    @Test
    void observerFailureDoesNotStopTheStreamFromBeingReclaimed() throws Exception {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = coordinator(streams, instances(), new CountingCommands(),
                    executor, Duration.ofSeconds(5), Duration.ofMillis(40), CLOCK,
                    (streamKey, mediaInstanceId, waitedMillis) -> {
                        throw new IllegalStateException("publisher down");
                    });

            coordinator.open(KEY);

            assertTrue(await(() -> streams.find(KEY).orElseThrow().state() == StreamState.DEAD, 500));
            assertEquals("DEVICE_NO_RESPONSE", streams.find(KEY).orElseThrow().terminalReason());
        }
    }

    @Test
    void liveAndPlaybackTargetsComeFromTheRegisteredMediaInstance() {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        instances.register(new MediaInstance(
                "media-custom",
                "203.0.113.42",
                new MediaPorts(49_100, 49_101, 49_102, 49_103, 49_104, 49_105, 49_106),
                100,
                1_000_000,
                0,
                0,
                NOW,
                false));
        CountingCommands commands = new CountingCommands();
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = coordinator(streams, instances, commands, executor,
                    Duration.ofSeconds(5), Duration.ofSeconds(5));

            coordinator.open(KEY);
            assertEquals("203.0.113.42", commands.liveTarget.reachableAddress());
            assertEquals(49_101, commands.liveTarget.tcpPort());

            StreamKey playback = new StreamKey("device-1", 1, StreamKind.PLAYBACK);
            LocalDateTime start = LocalDateTime.of(2026, 8, 10, 12, 30);
            LocalDateTime end = LocalDateTime.of(2026, 8, 10, 13, 45);
            coordinator.openPlayback(playback, start, end);
            assertEquals("203.0.113.42", commands.playbackTarget.reachableAddress());
            assertEquals(49_103, commands.playbackTarget.tcpPort());
            assertEquals(start, commands.playbackStart);
            assertEquals(end, commands.playbackEnd);

            coordinator.onFirstPacket(KEY, "media-custom");
            coordinator.onFirstPacket(playback, "media-custom");
        }
    }

    private static StreamCoordinator coordinator(
            InMemoryStreamRegistry streams,
            InMemoryMediaInstanceRegistry instances,
            CountingCommands commands,
            java.util.concurrent.ScheduledExecutorService executor,
            Duration idle,
            Duration pending) {
        MediaScheduler scheduler = new MediaScheduler(instances, streams, CLOCK, Duration.ofSeconds(15), 0.9);
        return new StreamCoordinator(streams, scheduler, commands, executor, CLOCK, idle, pending, () -> "stream-1");
    }

    private static StreamCoordinator coordinator(
            InMemoryStreamRegistry streams,
            InMemoryMediaInstanceRegistry instances,
            CountingCommands commands,
            java.util.concurrent.ScheduledExecutorService executor,
            Duration idle,
            Duration pending,
            Clock clock,
            io.github.jtplatform.common.port.StreamNotArrivedListener listener) {
        MediaScheduler scheduler = new MediaScheduler(instances, streams, clock, Duration.ofSeconds(15), 0.9);
        return new StreamCoordinator(streams, scheduler, commands, executor, clock, idle, pending,
                () -> "stream-1", listener);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant = NOW;

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static InMemoryMediaInstanceRegistry instances() {
        InMemoryMediaInstanceRegistry registry = new InMemoryMediaInstanceRegistry();
        registry.register(new MediaInstance("media-1", "127.0.0.1", MediaPorts.forInstance(1),
                100, 1_000_000, 0, 0, NOW, false));
        return registry;
    }

    private static boolean await(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    private static final class CountingCommands implements StreamCommandPort {
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile MediaTarget liveTarget;
        private volatile MediaTarget playbackTarget;
        private volatile LocalDateTime playbackStart;
        private volatile LocalDateTime playbackEnd;

        @Override
        public StreamTicket openLive(StreamKey streamKey, MediaTarget target) {
            openCount.incrementAndGet();
            liveTarget = target;
            return new StreamTicket(streamKey, "signal-ticket", target, target.websocketUri("/ws"),
                    StreamState.PENDING, NOW);
        }

        @Override
        public StreamTicket openPlayback(
                StreamKey streamKey,
                MediaTarget target,
                LocalDateTime startTime,
                LocalDateTime endTime) {
            openCount.incrementAndGet();
            playbackTarget = target;
            playbackStart = startTime;
            playbackEnd = endTime;
            return new StreamTicket(streamKey, "signal-ticket", target, target.websocketUri("/ws"),
                    StreamState.PENDING, NOW);
        }

        @Override
        public void close(StreamKey streamKey) {
            closeCount.incrementAndGet();
        }
    }
}
