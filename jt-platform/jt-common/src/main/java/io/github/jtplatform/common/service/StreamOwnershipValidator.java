package io.github.jtplatform.common.service;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.port.StreamRegistry;
import java.util.Objects;

public final class StreamOwnershipValidator {
    private final StreamRegistry streams;

    public StreamOwnershipValidator(StreamRegistry streams) {
        this.streams = Objects.requireNonNull(streams, "streams");
    }

    public boolean owns(StreamKey streamKey, String mediaInstanceId) {
        return streams.find(streamKey)
                .filter(entry -> entry.mediaInstanceId().equals(mediaInstanceId))
                .filter(entry -> entry.state() != io.github.jtplatform.common.model.StreamState.DEAD)
                .isPresent();
    }
}
