package io.github.jtplatform.api.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.api.auth.DisabledStreamRequestAuthenticator;
import io.github.jtplatform.common.auth.InMemoryStreamTokenStore;
import io.github.jtplatform.common.auth.TokenValidationResult;
import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.RecordingTimeRange;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.model.StreamTicket;
import io.github.jtplatform.common.port.InMemoryMediaInstanceRegistry;
import io.github.jtplatform.common.port.InMemoryStreamRegistry;
import io.github.jtplatform.common.port.RecordingCatalog;
import io.github.jtplatform.common.port.StreamCommandPort;
import io.github.jtplatform.common.service.MediaScheduler;
import io.github.jtplatform.common.service.NoMediaCapacityException;
import io.github.jtplatform.common.service.StreamCoordinator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamOpenServiceTest {
    @Test
    void repeatedOpenReusesStreamButIssuesSeparateConnectionTokens() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        instances.register(new MediaInstance("media-1", "127.0.0.1", MediaPorts.forInstance(1),
                100, 1_000_000, 0, 0, now, false));
        CountingCommands commands = new CountingCommands(now);
        InMemoryStreamTokenStore tokens = new InMemoryStreamTokenStore(new SecureRandom(), clock);

        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = new StreamCoordinator(streams,
                    new MediaScheduler(instances, streams, clock, Duration.ofSeconds(15), 0.9),
                    commands, executor, clock, Duration.ofSeconds(60), Duration.ofSeconds(30), () -> "stream-1");
            StreamOpenService service = new StreamOpenService(new DisabledStreamRequestAuthenticator(),
                    coordinator, tokens, Duration.ofSeconds(60));
            OpenStreamRequest request = new OpenStreamRequest("device-1", 1, StreamKind.MAIN);

            OpenStreamResponse first = service.open(null, request);
            OpenStreamResponse second = service.open(null, request);

            assertEquals("stream-1", first.streamId());
            assertEquals(first.wsUrl(), second.wsUrl());
            assertEquals("ws://127.0.0.1:7815/ws?deviceId=device-1&channel=1&streamKind=main",
                    first.wsUrl());
            assertNotEquals(first.token(), second.token());
            assertEquals("waking", first.state());
            assertEquals(1, commands.openCount.get());
            assertEquals(TokenValidationResult.VALID, tokens.validateAndConsume(first.token(),
                    request.streamKey(), "media-1"));
            coordinator.onFirstPacket(request.streamKey(), "media-1");

            OpenStreamResponse live = service.open(null, request);
            assertEquals("live", live.state());
            assertEquals(first.wsUrl(), live.wsUrl());
            assertEquals(1, commands.openCount.get());
        }
    }

    @Test
    void noMediaCapacityFailsClearlyWithoutSendingACommand() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        CountingCommands commands = new CountingCommands(now);

        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = new StreamCoordinator(streams,
                    new MediaScheduler(new InMemoryMediaInstanceRegistry(), streams, clock,
                            Duration.ofSeconds(15), 0.9),
                    commands, executor, clock, Duration.ofSeconds(60), Duration.ofSeconds(30));
            StreamOpenService service = new StreamOpenService(new DisabledStreamRequestAuthenticator(),
                    coordinator, new InMemoryStreamTokenStore(new SecureRandom(), clock), Duration.ofSeconds(60));

            assertThrows(NoMediaCapacityException.class,
                    () -> service.open(null, new OpenStreamRequest("device-1", 1, StreamKind.MAIN)));
            assertEquals(0, commands.openCount.get());
        }
    }

    @Test
    void playbackRequiresAndForwardsTheRequestedUtcRange() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        Instant start = Instant.parse("2026-08-09T12:00:00.123456Z");
        Instant end = Instant.parse("2026-08-09T12:30:00.654321Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        instances.register(new MediaInstance("media-1", "127.0.0.1", MediaPorts.forInstance(1),
                100, 1_000_000, 0, 0, now, false));
        CountingCommands commands = new CountingCommands(now);
        InMemoryStreamTokenStore tokens = new InMemoryStreamTokenStore(new SecureRandom(), clock);

        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = new StreamCoordinator(streams,
                    new MediaScheduler(instances, streams, clock, Duration.ofSeconds(15), 0.9),
                    commands, executor, clock, Duration.ofSeconds(60), Duration.ofSeconds(30));
            StreamOpenService service = new StreamOpenService(new DisabledStreamRequestAuthenticator(),
                    coordinator, tokens, Duration.ofSeconds(60));
            OpenStreamRequest request = new OpenStreamRequest(
                    "device-playback", 2, StreamKind.PLAYBACK, start, end);

            OpenStreamResponse response = service.open(null, request);
            coordinator.onFirstPacket(request.streamKey(), "media-1");

            assertEquals(1, commands.playbackOpenCount.get());
            assertEquals(LocalDateTime.ofInstant(start, ZoneOffset.UTC), commands.playbackStart);
            assertEquals(LocalDateTime.ofInstant(end, ZoneOffset.UTC), commands.playbackEnd);
            assertTrue(response.wsUrl().contains("streamKind=playback"));
            assertTrue(response.wsUrl().contains("startTime=2026-08-09T12%3A00%3A00.123456Z"));
            assertTrue(response.wsUrl().contains("endTime=2026-08-09T12%3A30%3A00.654321Z"));
            assertEquals(TokenValidationResult.VALID, tokens.validateAndConsume(
                    response.token(), request.streamKey(), "media-1"));
        }

        assertThrows(IllegalArgumentException.class,
                () -> new OpenStreamRequest("device-playback", 2, StreamKind.PLAYBACK));
    }

    @Test
    void localPlaybackDoesNotSendDeviceCommands() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        Instant start = Instant.parse("2026-08-09T12:00:00Z");
        Instant end = Instant.parse("2026-08-09T12:30:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        instances.register(new MediaInstance("media-1", "127.0.0.1", MediaPorts.forInstance(1),
                100, 1_000_000, 0, 0, now, false));
        CountingCommands commands = new CountingCommands(now);
        RecordingCatalog recordings = new RecordingCatalog() {
            @Override
            public List<RecordingTimeRange> search(
                    StreamKey streamKey, long startTimestampUs, long endTimestampUs) {
                return List.of(new RecordingTimeRange(startTimestampUs, endTimestampUs));
            }

            @Override
            public List<RecordingTimeRange> searchAll(
                    String deviceId, int channel, long startTimestampUs, long endTimestampUs) {
                return List.of(new RecordingTimeRange(startTimestampUs, endTimestampUs));
            }
        };

        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = new StreamCoordinator(streams,
                    new MediaScheduler(instances, streams, clock, Duration.ofSeconds(15), 0.9),
                    commands, executor, clock, Duration.ofSeconds(60), Duration.ofSeconds(30));
            StreamOpenService service = new StreamOpenService(new DisabledStreamRequestAuthenticator(),
                    coordinator, new InMemoryStreamTokenStore(new SecureRandom(), clock),
                    recordings, Duration.ofSeconds(60));
            OpenStreamRequest request = new OpenStreamRequest(
                    "device-playback", 2, StreamKind.PLAYBACK, start, end);

            OpenStreamResponse response = service.open(null, request);

            assertEquals("live", response.state());
            assertEquals(StreamState.LIVE, streams.find(request.streamKey()).orElseThrow().state());
            assertEquals(0, commands.playbackOpenCount.get());
            coordinator.closeNow(request.streamKey(), "test complete");
            assertEquals(0, commands.closeCount.get());
        }
    }

    private static final class CountingCommands implements StreamCommandPort {
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger playbackOpenCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final Instant now;
        private LocalDateTime playbackStart;
        private LocalDateTime playbackEnd;

        private CountingCommands(Instant now) {
            this.now = now;
        }

        @Override
        public StreamTicket openLive(StreamKey streamKey, MediaTarget target) {
            openCount.incrementAndGet();
            return new StreamTicket(streamKey, "command-1", target, target.websocketUri("/ws"),
                    StreamState.PENDING, now);
        }

        @Override
        public StreamTicket openPlayback(
                StreamKey streamKey,
                MediaTarget target,
                LocalDateTime startTime,
                LocalDateTime endTime) {
            playbackOpenCount.incrementAndGet();
            playbackStart = startTime;
            playbackEnd = endTime;
            return new StreamTicket(streamKey, "playback-command-1", target,
                    target.websocketUri("/ws"), StreamState.PENDING, now);
        }

        @Override
        public void close(StreamKey streamKey) {
            closeCount.incrementAndGet();
        }
    }
}
