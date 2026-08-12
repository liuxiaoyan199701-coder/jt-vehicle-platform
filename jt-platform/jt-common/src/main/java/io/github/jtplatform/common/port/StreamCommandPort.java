package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamTicket;
import java.time.LocalDateTime;

public interface StreamCommandPort {
    StreamTicket openLive(StreamKey streamKey, MediaTarget target);

    StreamTicket openPlayback(
            StreamKey streamKey,
            MediaTarget target,
            LocalDateTime startTime,
            LocalDateTime endTime);

    void close(StreamKey streamKey);
}
