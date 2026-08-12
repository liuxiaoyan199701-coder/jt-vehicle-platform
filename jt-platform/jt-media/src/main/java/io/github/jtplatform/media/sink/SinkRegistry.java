package io.github.jtplatform.media.sink;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.frame.MediaFrame;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SinkRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(SinkRegistry.class);

    private final CopyOnWriteArrayList<MediaSink> sinks = new CopyOnWriteArrayList<>();

    public void register(MediaSink sink) {
        sinks.addIfAbsent(Objects.requireNonNull(sink, "sink"));
    }

    public boolean unregister(MediaSink sink) {
        return sinks.remove(sink);
    }

    public List<MediaSink> registeredSinks() {
        return List.copyOf(sinks);
    }

    public void dispatch(MediaFrame frame) {
        Objects.requireNonNull(frame, "frame");
        for (MediaSink sink : sinks) {
            try {
                sink.accept(frame);
            } catch (RuntimeException failure) {
                LOGGER.error("Media sink {} failed for stream {}; continuing with remaining sinks",
                        sink.getClass().getName(), frame.streamKey().externalId(), failure);
            }
        }
    }

    public void streamClosed(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        for (MediaSink sink : sinks) {
            try {
                sink.onStreamClosed(streamKey);
            } catch (RuntimeException failure) {
                LOGGER.error("Media sink {} failed to close stream {}; continuing with remaining sinks",
                        sink.getClass().getName(), streamKey.externalId(), failure);
            }
        }
    }
}
