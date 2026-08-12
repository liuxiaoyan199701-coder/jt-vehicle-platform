package io.github.jtplatform.signal.config;

import io.github.jtplatform.common.model.SignalPorts;
import java.util.Objects;

public final class SignalPortResolver {
    private SignalPortResolver() {
    }

    public static SignalServerSettings resolve(
            SignalProperties properties,
            int instanceNumber,
            boolean clusterProfile) {
        Objects.requireNonNull(properties, "properties");
        SignalPorts backend = SignalPorts.forInstance(instanceNumber);
        int tcpPort = properties.getTcpPort() != null
                ? properties.getTcpPort()
                : clusterProfile ? backend.tcp() : properties.getPublicTcpPort();
        int udpPort = properties.getUdpPort() != null
                ? properties.getUdpPort()
                : clusterProfile ? backend.udp() : properties.getPublicUdpPort();
        return new SignalServerSettings(
                properties.getInstanceId(),
                properties.getPublicTcpPort(),
                properties.getPublicUdpPort(),
                backend,
                tcpPort,
                udpPort);
    }
}
