package io.github.jtplatform.common.model;

import java.time.Instant;
import java.util.Objects;

public record MediaInstance(
        String instanceId,
        String reachableAddress,
        MediaPorts ports,
        int maxStreams,
        long maxOutboundBitsPerSecond,
        int currentStreams,
        long outboundBitsPerSecond,
        Instant heartbeatAt,
        boolean draining) {

    public MediaInstance {
        instanceId = requireText(instanceId, "instanceId");
        reachableAddress = requireText(reachableAddress, "reachableAddress");
        Objects.requireNonNull(ports, "ports");
        Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        if (maxStreams < 1) {
            throw new IllegalArgumentException("maxStreams must be positive");
        }
        if (maxOutboundBitsPerSecond < 1) {
            throw new IllegalArgumentException("maxOutboundBitsPerSecond must be positive");
        }
        if (currentStreams < 0 || outboundBitsPerSecond < 0) {
            throw new IllegalArgumentException("load values must not be negative");
        }
    }

    public boolean hasCapacity(double saturationThreshold) {
        if (draining || saturationThreshold <= 0 || saturationThreshold > 1) {
            return false;
        }
        return currentStreams < Math.ceil(maxStreams * saturationThreshold)
                && outboundBitsPerSecond < Math.ceil(maxOutboundBitsPerSecond * saturationThreshold);
    }

    public double bandwidthUtilization() {
        return (double) outboundBitsPerSecond / maxOutboundBitsPerSecond;
    }

    public double streamUtilization() {
        return (double) currentStreams / maxStreams;
    }

    public MediaTarget targetFor(StreamKind kind) {
        return new MediaTarget(instanceId, reachableAddress, ports.ingestPort(kind), 0, ports.websocket());
    }

    public MediaInstance withLoad(int streams, long outboundBits, Instant heartbeat) {
        return new MediaInstance(instanceId, reachableAddress, ports, maxStreams, maxOutboundBitsPerSecond,
                streams, outboundBits, heartbeat, draining);
    }

    public MediaInstance asDraining(Instant heartbeat) {
        return new MediaInstance(instanceId, reachableAddress, ports, maxStreams, maxOutboundBitsPerSecond,
                currentStreams, outboundBitsPerSecond, heartbeat, true);
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }
}
