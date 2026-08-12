package io.github.jtplatform.media.sink;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.frame.MediaFrame;

@FunctionalInterface
public interface MediaSink {
    void accept(MediaFrame frame);

    default void onStreamClosed(StreamKey streamKey) {
    }
}
