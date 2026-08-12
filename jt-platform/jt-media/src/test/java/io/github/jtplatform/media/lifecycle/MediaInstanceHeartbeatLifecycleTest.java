package io.github.jtplatform.media.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.config.AddressSource;
import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.port.InMemoryMediaInstanceRegistry;
import io.github.jtplatform.common.port.InMemoryStreamRegistry;
import io.github.jtplatform.media.config.MediaRuntimeProperties;
import io.github.jtplatform.media.metrics.MediaNodeLoadMonitor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MediaInstanceHeartbeatLifecycleTest {
    @Test
    void registersReachablePortsAndCapacityThenReportsLoadAndExpiresStaleInstances() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        AtomicInteger activeStreams = new AtomicInteger(2);
        AtomicLong outboundBytes = new AtomicLong();
        MediaNodeLoadMonitor load = new MediaNodeLoadMonitor(activeStreams::get, outboundBytes::get, clock);
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        MediaRuntimeProperties properties = properties(Duration.ofSeconds(10), Duration.ofSeconds(15));
        MediaPorts ports = MediaPorts.forInstance(2);

        MediaInstance stale = new MediaInstance("media-stale", "192.0.2.30", MediaPorts.forInstance(3),
                10, 10_000, 1, 100, clock.instant().minusSeconds(20), false);
        instances.register(stale);
        StreamKey staleKey = new StreamKey("device-stale", 1, StreamKind.MAIN);
        streams.getOrRegister(staleKey, () -> new StreamEntry(staleKey, "stream-stale", "media-stale",
                stale.targetFor(StreamKind.MAIN), clock));

        MediaInstanceHeartbeatLifecycle lifecycle = new MediaInstanceHeartbeatLifecycle(
                instances, streams, load, properties, settings -> settings.value(), clock, () -> true, () -> ports);
        try {
            lifecycle.start();
            MediaInstance registered = instances.find("media-2").orElseThrow();
            assertEquals("192.0.2.20", registered.reachableAddress());
            assertEquals(ports, registered.ports());
            assertEquals(200, registered.maxStreams());
            assertEquals(2_000_000, registered.maxOutboundBitsPerSecond());
            assertEquals(2, registered.currentStreams());

            activeStreams.set(3);
            outboundBytes.set(125);
            clock.advance(Duration.ofSeconds(1));
            lifecycle.heartbeatNow();

            registered = instances.find("media-2").orElseThrow();
            assertEquals(3, registered.currentStreams());
            assertEquals(1_000, registered.outboundBitsPerSecond());
            assertTrue(instances.find("media-stale").isEmpty());
            assertEquals(StreamState.DEAD, streams.find(staleKey).orElseThrow().state());
        } finally {
            lifecycle.stop();
        }
        assertTrue(instances.find("media-2").orElseThrow().draining());
    }

    @Test
    void renewsHeartbeatOnItsConfiguredSchedule() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        MediaRuntimeProperties properties = properties(Duration.ofMillis(20), Duration.ofSeconds(1));
        MediaInstanceHeartbeatLifecycle lifecycle = new MediaInstanceHeartbeatLifecycle(
                instances,
                new InMemoryStreamRegistry(),
                new MediaNodeLoadMonitor(() -> 0, () -> 0L, clock),
                properties,
                settings -> settings.value(),
                clock,
                () -> true,
                () -> MediaPorts.forInstance(2));
        try {
            lifecycle.start();
            Instant firstHeartbeat = instances.find("media-2").orElseThrow().heartbeatAt();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!instances.find("media-2").orElseThrow().heartbeatAt().isAfter(firstHeartbeat)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(instances.find("media-2").orElseThrow().heartbeatAt().isAfter(firstHeartbeat));
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void stoppedListenerLosesItsRegistrationAndAllOwnedStreamsAfterHeartbeatTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        AtomicBoolean nodeRunning = new AtomicBoolean(true);
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        MediaRuntimeProperties properties = properties(Duration.ofSeconds(10), Duration.ofSeconds(15));
        MediaInstanceHeartbeatLifecycle lifecycle = new MediaInstanceHeartbeatLifecycle(
                instances,
                streams,
                new MediaNodeLoadMonitor(() -> 1, () -> 0L, clock),
                properties,
                settings -> settings.value(),
                clock,
                nodeRunning::get,
                () -> MediaPorts.forInstance(2));
        try {
            lifecycle.start();
            MediaInstance instance = instances.find("media-2").orElseThrow();
            StreamKey key = new StreamKey("device-2", 1, StreamKind.MAIN);
            streams.getOrRegister(key, () -> new StreamEntry(key, "stream-2", "media-2",
                    instance.targetFor(StreamKind.MAIN), clock));

            nodeRunning.set(false);
            clock.advance(Duration.ofSeconds(16));
            lifecycle.heartbeatNow();

            assertTrue(instances.find("media-2").isEmpty());
            assertEquals(StreamState.DEAD, streams.find(key).orElseThrow().state());
        } finally {
            lifecycle.stop();
        }
    }

    private static MediaRuntimeProperties properties(Duration heartbeatInterval, Duration heartbeatTtl) {
        MediaRuntimeProperties properties = new MediaRuntimeProperties();
        properties.setInstanceId("media-2");
        properties.getReachableAddress().setSource(AddressSource.STATIC);
        properties.getReachableAddress().setValue("192.0.2.20");
        properties.getCapacity().setMaxStreams(200);
        properties.getCapacity().setMaxOutboundBitsPerSecond(2_000_000);
        properties.setHeartbeatInterval(heartbeatInterval);
        properties.setHeartbeatTtl(heartbeatTtl);
        return properties;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
