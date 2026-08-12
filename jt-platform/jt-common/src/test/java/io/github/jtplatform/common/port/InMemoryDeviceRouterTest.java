package io.github.jtplatform.common.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InMemoryDeviceRouterTest {
    @Test
    void onlyTheOwningSignalInstanceCanRemoveARoute() {
        InMemoryDeviceRouter router = new InMemoryDeviceRouter();
        router.bind("device-1", "signal-1");
        router.unbind("device-1", "signal-2");
        assertEquals("signal-1", router.findSignalInstance("device-1").orElseThrow());
        router.unbind("device-1", "signal-1");
        assertTrue(router.findSignalInstance("device-1").isEmpty());
    }
}
