package io.github.jtplatform.common.model;

import java.net.URI;
import java.util.Objects;

public record MediaTarget(
        String instanceId,
        String reachableAddress,
        int tcpPort,
        int udpPort,
        int websocketPort) {

    public MediaTarget {
        instanceId = requireText(instanceId, "instanceId");
        reachableAddress = requireText(reachableAddress, "reachableAddress");
        validatePort(tcpPort, "tcpPort", false);
        validatePort(udpPort, "udpPort", true);
        validatePort(websocketPort, "websocketPort", false);
    }

    public URI websocketUri(String path) {
        String normalizedPath = path == null || path.isBlank() ? "/ws" : path;
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = '/' + normalizedPath;
        }
        return URI.create("ws://" + reachableAddress + ':' + websocketPort + normalizedPath);
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }

    private static void validatePort(int port, String name, boolean allowZero) {
        int minimum = allowZero ? 0 : 1;
        if (port < minimum || port > 65_535) {
            throw new IllegalArgumentException(name + " must be in range " + minimum + "..65535");
        }
    }
}
