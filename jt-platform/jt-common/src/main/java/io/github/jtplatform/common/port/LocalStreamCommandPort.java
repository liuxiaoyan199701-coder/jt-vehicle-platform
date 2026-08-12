package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamTicket;
import java.time.LocalDateTime;
import java.util.Objects;

public final class LocalStreamCommandPort implements StreamCommandPort {
    private final StreamCommandHandler handler;

    public LocalStreamCommandPort(StreamCommandHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public StreamTicket openLive(StreamKey streamKey, MediaTarget target) {
        return handler.openLive(streamKey, target);
    }

    @Override
    public StreamTicket openPlayback(
            StreamKey streamKey,
            MediaTarget target,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        return handler.openPlayback(streamKey, target, startTime, endTime);
    }

    @Override
    public void close(StreamKey streamKey) {
        handler.close(streamKey);
    }
}
