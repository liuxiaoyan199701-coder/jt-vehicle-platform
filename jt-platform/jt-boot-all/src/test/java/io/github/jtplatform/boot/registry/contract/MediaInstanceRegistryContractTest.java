package io.github.jtplatform.boot.registry.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

public abstract class MediaInstanceRegistryContractTest {
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    private MediaInstanceRegistry registry;

    protected abstract MediaInstanceRegistry newRegistry();

    protected final MediaInstanceRegistry registry() {
        if (registry == null) {
            registry = newRegistry();
        }
        return registry;
    }

    protected static MediaInstance instance(String id, Instant heartbeatAt, boolean draining) {
        return new MediaInstance(id, "10.0.0.1", MediaPorts.forInstance(1), 100, 1_000_000L,
                0, 0, heartbeatAt, draining);
    }

    @Test
    void registerAndFind() {
        registry().register(instance("media-1", NOW, false));
        assertEquals("media-1", registry().find("media-1").orElseThrow().instanceId());
    }

    @Test
    void updateLoadUnknownInstanceThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> registry().updateLoad("media-9", 1, 1000L, NOW));
    }

    @Test
    void markDrainingSetsFlag() {
        registry().register(instance("media-1", NOW, false));
        registry().markDraining("media-1", NOW);
        assertTrue(registry().find("media-1").orElseThrow().draining());
    }

    @Test
    void activeAfterFiltersExpiredAndDraining() {
        registry().register(instance("fresh", NOW, false));
        registry().register(instance("stale", NOW.minusSeconds(60), false));
        registry().register(instance("draining", NOW, true));

        var active = registry().activeAfter(NOW.minusSeconds(10));
        assertEquals(List.of("fresh"), active.stream().map(MediaInstance::instanceId).toList());
    }

    @Test
    void removeExpiredBeforeRemovesOnlyExpired() {
        registry().register(instance("fresh", NOW, false));
        registry().register(instance("stale", NOW.minusSeconds(60), false));

        assertEquals(List.of("stale"), registry().removeExpiredBefore(NOW.minusSeconds(10)));
        assertFalse(registry().find("stale").isPresent());
        assertTrue(registry().find("fresh").isPresent());
    }

    @Test
    void allReturnsAllRegistered() {
        registry().register(instance("a", NOW, false));
        registry().register(instance("b", NOW, false));
        assertEquals(2, registry().all().size());
    }
}
