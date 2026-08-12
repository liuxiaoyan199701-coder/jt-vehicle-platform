package io.github.jtplatform.signal.config;

import io.github.jtplatform.common.model.SignalPorts;

public record SignalServerSettings(
        String instanceId,
        int publicTcpPort,
        int publicUdpPort,
        SignalPorts backendPorts,
        int listenTcpPort,
        int listenUdpPort) {

    public SignalServerSettings {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        validatePort(publicTcpPort, "publicTcpPort");
        validatePort(publicUdpPort, "publicUdpPort");
        if (backendPorts == null) {
            throw new NullPointerException("backendPorts");
        }
        validatePort(listenTcpPort, "listenTcpPort");
        validatePort(listenUdpPort, "listenUdpPort");
    }

    private static void validatePort(int port, String name) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(name + " must be in range 1..65535");
        }
    }
}
