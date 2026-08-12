package io.github.jtplatform.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.port.InMemoryMediaInstanceRegistry;
import io.github.jtplatform.common.port.InMemoryStreamRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MediaSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void bandwidthIsPrimaryAndNewInstancesParticipateImmediately() {
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        instances.register(instance("media-a", 1, 1, 800));
        instances.register(instance("media-b", 2, 20, 200));
        MediaScheduler scheduler = new MediaScheduler(instances, streams, CLOCK, Duration.ofSeconds(15), 0.9);

        assertEquals("media-b", scheduler.pick(new StreamKey("device-1", 1, StreamKind.MAIN)).instanceId());

        instances.register(instance("media-c", 3, 0, 0));
        assertEquals("media-c", scheduler.pick(new StreamKey("device-2", 1, StreamKind.MAIN)).instanceId());
    }

    @Test
    void deviceAffinityWinsOverLowerLoad() {
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        MediaInstance a = instance("media-a", 1, 10, 500);
        instances.register(a);
        instances.register(instance("media-b", 2, 0, 0));
        StreamKey existing = new StreamKey("device-1", 1, StreamKind.MAIN);
        streams.getOrRegister(existing, () -> new StreamEntry(existing, "stream-1", "media-a",
                a.targetFor(StreamKind.MAIN), CLOCK));
        MediaScheduler scheduler = new MediaScheduler(instances, streams, CLOCK, Duration.ofSeconds(15), 0.9);

        assertEquals("media-a", scheduler.pick(new StreamKey("device-1", 1, StreamKind.TALKBACK)).instanceId());
    }

    @Test
    void deadStreamsReleaseDeviceAffinity() {
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        MediaInstance a = instance("media-a", 1, 10, 500);
        instances.register(a);
        instances.register(instance("media-b", 2, 0, 0));
        StreamKey existing = new StreamKey("device-1", 1, StreamKind.MAIN);
        streams.getOrRegister(existing, () -> new StreamEntry(existing, "stream-1", "media-a",
                a.targetFor(StreamKind.MAIN), CLOCK));
        streams.markDead(existing, "released");
        MediaScheduler scheduler = new MediaScheduler(instances, streams, CLOCK, Duration.ofSeconds(15), 0.9);

        assertEquals("media-b", scheduler.pick(new StreamKey("device-1", 1, StreamKind.TALKBACK)).instanceId());
    }

    @Test
    void rejectsExpiredOrSaturatedInstances() {
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        instances.register(new MediaInstance("expired", "127.0.0.1", MediaPorts.forInstance(1),
                100, 1_000, 0, 0, NOW.minusSeconds(30), false));
        instances.register(instance("full", 2, 90, 900));
        MediaScheduler scheduler = new MediaScheduler(instances, streams, CLOCK, Duration.ofSeconds(15), 0.9);

        assertThrows(NoMediaCapacityException.class,
                () -> scheduler.pick(new StreamKey("device-1", 1, StreamKind.MAIN)));
    }

    private static MediaInstance instance(String id, int number, int streams, long bandwidth) {
        return new MediaInstance(id, "127.0.0.1", MediaPorts.forInstance(number), 100, 1_000,
                streams, bandwidth, NOW, false);
    }
}
