package io.github.jtplatform.common.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record StreamTicket(
        StreamKey streamKey,
        String streamId,
        MediaTarget target,
        URI websocketUri,
        StreamState state,
        Instant issuedAt) {

    public StreamTicket {
        Objects.requireNonNull(streamKey, "streamKey");
        if (streamId == null || streamId.isBlank()) {
            throw new IllegalArgumentException("streamId must not be blank");
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(websocketUri, "websocketUri");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    public static StreamTicket from(StreamEntry entry) {
        return new StreamTicket(entry.streamKey(), entry.streamId(), entry.mediaTarget(),
                entry.mediaTarget().websocketUri("/ws"), entry.state(), Instant.now());
    }
}
