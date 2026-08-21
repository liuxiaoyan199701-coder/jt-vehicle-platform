package io.github.jtplatform.boot.registry.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.port.StreamRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public abstract class StreamRegistryContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
    private static final StreamKey KEY = new StreamKey("device-1", 1, StreamKind.MAIN);
    private static final MediaTarget TARGET = new MediaTarget("media-1", "127.0.0.1",
            MediaPorts.forInstance(1).main(), 0, MediaPorts.forInstance(1).websocket());

    private StreamRegistry registry;

    protected abstract StreamRegistry newRegistry();

    protected final StreamRegistry registry() {
        if (registry == null) {
            registry = newRegistry();
        }
        return registry;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (registry instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    protected static StreamEntry newEntry(StreamKey key, String streamId, String instanceId) {
        return new StreamEntry(key, streamId, instanceId, TARGET, CLOCK);
    }

    @Test
    void getOrRegisterCreatesOnceAndReuses() {
        AtomicInteger factoryCalls = new AtomicInteger();
        StreamRegistry.Registration first = registry().getOrRegister(KEY,
                () -> {
                    factoryCalls.incrementAndGet();
                    return newEntry(KEY, "stream-1", "media-1");
                });
        StreamRegistry.Registration second = registry().getOrRegister(KEY,
                () -> {
                    factoryCalls.incrementAndGet();
                    return newEntry(KEY, "stream-2", "media-2");
                });

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(1, factoryCalls.get());
        assertEquals(KEY, second.entry().streamKey());
        assertEquals(StreamState.PENDING, registry().find(KEY).orElseThrow().state());
    }

    @Test
    void stateMachinePendingLiveDead() {
        registry().getOrRegister(KEY, () -> newEntry(KEY, "stream-1", "media-1"));
        assertEquals(StreamState.PENDING, registry().find(KEY).orElseThrow().state());

        assertTrue(registry().markLive(KEY));
        assertEquals(StreamState.LIVE, registry().find(KEY).orElseThrow().state());

        assertTrue(registry().markDead(KEY, "closed"));
        assertEquals(StreamState.DEAD, registry().find(KEY).orElseThrow().state());

        assertFalse(registry().markLive(KEY));
    }

    @Test
    void subscriberCountTracksSubscriptions() {
        registry().getOrRegister(KEY, () -> newEntry(KEY, "stream-1", "media-1"));
        assertEquals(1, registry().addSubscriber(KEY));
        assertEquals(2, registry().addSubscriber(KEY));
        assertEquals(1, registry().removeSubscriber(KEY));
        assertEquals(0, registry().removeSubscriber(KEY));
    }

    @Test
    void deadStreamIsReplacedOnRegister() {
        registry().getOrRegister(KEY, () -> newEntry(KEY, "stream-1", "media-1"));
        registry().markDead(KEY, "closed");
        StreamRegistry.Registration replacement =
                registry().getOrRegister(KEY, () -> newEntry(KEY, "stream-2", "media-1"));
        assertTrue(replacement.created());
        assertEquals(StreamState.PENDING, registry().find(KEY).orElseThrow().state());
    }

    @Test
    void expirePendingBeforeMarksStalePendingDead() {
        registry().getOrRegister(KEY, () -> newEntry(KEY, "stream-1", "media-1"));
        List<StreamKey> expired = registry().expirePendingBefore(CLOCK.instant().plusSeconds(1), "timeout");
        assertEquals(List.of(KEY), expired);
        assertEquals(StreamState.DEAD, registry().find(KEY).orElseThrow().state());
    }

    @Test
    void invalidateMediaInstanceMarksItsStreamsDead() {
        registry().getOrRegister(KEY, () -> newEntry(KEY, "stream-1", "media-1"));
        registry().markLive(KEY);
        assertEquals(1, registry().invalidateMediaInstance("media-1", "node down"));
        assertEquals(StreamState.DEAD, registry().find(KEY).orElseThrow().state());
    }

    @Test
    void concurrentRegistrationCreatesExactlyOneStream() throws Exception {
        AtomicInteger created = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Future<Boolean>> futures = executor.invokeAll(java.util.Collections.nCopies(50,
                    (Callable<Boolean>) () -> registry().getOrRegister(KEY,
                            () -> newEntry(KEY, "stream-1", "media-1")).created()));
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    created.incrementAndGet();
                }
            }
        }
        assertEquals(1, created.get());
        assertEquals(1, registry().entries().size());
    }
}
