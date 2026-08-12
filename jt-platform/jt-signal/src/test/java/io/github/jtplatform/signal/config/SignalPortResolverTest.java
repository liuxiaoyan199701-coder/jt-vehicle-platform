package io.github.jtplatform.signal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SignalPortResolverTest {
    @Test
    void standaloneListensOnPublicEntryPorts() {
        SignalServerSettings settings = SignalPortResolver.resolve(new SignalProperties(), 1, false);

        assertEquals(7100, settings.listenTcpPort());
        assertEquals(7101, settings.listenUdpPort());
        assertEquals(7110, settings.backendPorts().management());
        assertEquals(7111, settings.backendPorts().tcp());
        assertEquals(7112, settings.backendPorts().udp());
        assertEquals(7113, settings.backendPorts().command());
    }

    @Test
    void clusterListensOnInstanceBackendPorts() {
        SignalServerSettings settings = SignalPortResolver.resolve(new SignalProperties(), 2, true);

        assertEquals(7121, settings.listenTcpPort());
        assertEquals(7122, settings.listenUdpPort());
        assertEquals(7120, settings.backendPorts().management());
        assertEquals(7123, settings.backendPorts().command());
    }

    @Test
    void explicitListenerPortsOverrideProfileDefaults() {
        SignalProperties properties = new SignalProperties();
        properties.setTcpPort(7200);
        properties.setUdpPort(7201);

        SignalServerSettings settings = SignalPortResolver.resolve(properties, 1, true);

        assertEquals(7200, settings.listenTcpPort());
        assertEquals(7201, settings.listenUdpPort());
    }
}
