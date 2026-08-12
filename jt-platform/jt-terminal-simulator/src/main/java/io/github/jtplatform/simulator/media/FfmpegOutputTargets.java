package io.github.jtplatform.simulator.media;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

public record FfmpegOutputTargets(URI h264, Optional<URI> g711a, URI mjpeg) {
    public FfmpegOutputTargets {
        h264 = requireTcp(h264, "h264");
        g711a = Objects.requireNonNull(g711a, "g711a").map(uri -> requireTcp(uri, "g711a"));
        mjpeg = requireTcp(mjpeg, "mjpeg");
    }

    private static URI requireTcp(URI value, String name) {
        URI uri = Objects.requireNonNull(value, name);
        if (!"tcp".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1) {
            throw new IllegalArgumentException(name + " must be a TCP URI with host and port");
        }
        return uri;
    }
}
