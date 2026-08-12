package io.github.jtplatform.simulator.stream;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

public record MediaTarget(String host, int port) {
    public MediaTarget {
        host = Objects.requireNonNull(host, "host").trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("media host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("media port must be in range 1..65535");
        }
    }

    public InetAddress resolve() throws UnknownHostException {
        InetAddress address = InetAddress.getByName(host);
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw new UnknownHostException("Unsupported media target address: " + host);
        }
        return address;
    }

    @Override
    public String toString() {
        return host + ':' + port;
    }
}
