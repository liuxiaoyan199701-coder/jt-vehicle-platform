package io.github.jtplatform.common.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryStreamRegistryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
    private static final StreamKey KEY = new StreamKey("device-1", 1, StreamKind.MAIN);
    private static final MediaTarget TARGET = new MediaTarget("media-1", "127.0.0.1",
            MediaPorts.forInstance(1).main(), 0, MediaPorts.forInstance(1).websocket());

    @Test
    void queryAndRegistrationIsAtomic() throws Exception {
        InMemoryStreamRegistry registry = new InMemoryStreamRegistry();
        AtomicInteger factoryCalls = new AtomicInteger();
        Callable<StreamRegistry.Registration> registration = () -> registry.getOrRegister(KEY, () -> {
            factoryCalls.incrementAndGet();
            return new StreamEntry(KEY, "stream-1", "media-1", TARGET, CLOCK);
        });

        try (var executor = Executors.newFixedThreadPool(12)) {
            List<java.util.concurrent.Future<StreamRegistry.Registration>> futures = executor.invokeAll(
                    java.util.Collections.nCopies(100, registration));
            StreamEntry first = futures.getFirst().get().entry();
            for (var future : futures) {
                assertSame(first, future.get().entry());
            }
        }

        assertEquals(1, factoryCalls.get());
        assertEquals(1, registry.entries().size());
    }

    @Test
    void enforcesStateMachineAndSubscriberCount() {
        InMemoryStreamRegistry registry = new InMemoryStreamRegistry();
        StreamRegistry.Registration registration = registry.getOrRegister(KEY,
                () -> new StreamEntry(KEY, "stream-1", "media-1", TARGET, CLOCK));

        assertTrue(registration.created());
        assertEquals(StreamState.PENDING, registration.entry().state());
        assertEquals(1, registry.addSubscriber(KEY));
        assertEquals(2, registry.addSubscriber(KEY));
        assertTrue(registry.markLive(KEY));
        assertEquals(StreamState.LIVE, registration.entry().state());
        assertEquals(1, registry.removeSubscriber(KEY));
        assertTrue(registry.markDead(KEY, "closed"));
        assertFalse(registry.markLive(KEY));
        assertEquals(StreamState.DEAD, registration.entry().state());
    }
}
