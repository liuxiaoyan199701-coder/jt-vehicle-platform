package io.github.jtplatform.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.port.InMemoryMediaInstanceRegistry;
import io.github.jtplatform.common.port.InMemoryStreamRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MediaInstanceLifecycleTest {
    @Test
    void removesExpiredInstanceAndInvalidatesItsStreams() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        MediaInstance expired = new MediaInstance("media-1", "127.0.0.1", MediaPorts.forInstance(1),
                100, 1_000, 0, 0, now.minusSeconds(20), false);
        instances.register(expired);
        StreamKey key = new StreamKey("device-1", 1, StreamKind.MAIN);
        streams.getOrRegister(key, () -> new StreamEntry(key, "stream-1", "media-1",
                expired.targetFor(StreamKind.MAIN), clock));

        MediaInstanceLifecycle lifecycle = new MediaInstanceLifecycle(instances, streams, clock,
                Duration.ofSeconds(15));
        assertEquals(java.util.List.of("media-1"), lifecycle.expireStaleInstances());
        assertTrue(instances.find("media-1").isEmpty());
        assertEquals(StreamState.DEAD, streams.find(key).orElseThrow().state());
        MediaScheduler scheduler = new MediaScheduler(instances, streams, clock,
                Duration.ofSeconds(15), 0.9);
        assertThrows(NoMediaCapacityException.class,
                () -> scheduler.pick(new StreamKey("device-2", 1, StreamKind.MAIN)));
    }
}
