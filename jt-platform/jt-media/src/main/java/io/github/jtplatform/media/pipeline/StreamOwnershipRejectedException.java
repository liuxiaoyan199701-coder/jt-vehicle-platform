package io.github.jtplatform.media.pipeline;

import io.github.jtplatform.common.model.StreamKey;
import java.util.Objects;

public final class StreamOwnershipRejectedException extends RuntimeException {
    private final StreamKey streamKey;

    public StreamOwnershipRejectedException(StreamKey streamKey) {
        super("Stream registry ownership rejected "
                + Objects.requireNonNull(streamKey, "streamKey").externalId());
        this.streamKey = streamKey;
    }

    public StreamKey streamKey() {
        return streamKey;
    }
}
