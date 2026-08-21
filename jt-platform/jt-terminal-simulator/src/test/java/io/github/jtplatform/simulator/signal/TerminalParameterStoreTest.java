package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jtplatform.simulator.config.TerminalManagementConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalParameterStoreTest {
    @Test
    void parameterUpdateIsVisibleToFullAndSelectedQueries() {
        TerminalParameterStore store = new TerminalParameterStore(TerminalManagementConfig.defaults());
        store.update(Map.of(0x0001, 5L, 0x0055, 90L));

        assertEquals(5L, store.all().get(0x0001));
        assertEquals(90L, store.select(new int[] {0x0055}).get(0x0055));
        assertEquals(5L, store.heartbeatSeconds());
    }

    @Test
    void missingHeartbeatParameterUsesProtocolDefault() {
        TerminalParameterStore store = new TerminalParameterStore(
                new TerminalManagementConfig(Map.of(0x0055, 80L)));
        assertEquals(30L, store.heartbeatSeconds());
    }
}
