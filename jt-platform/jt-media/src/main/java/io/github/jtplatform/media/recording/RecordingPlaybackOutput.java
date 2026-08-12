package io.github.jtplatform.media.recording;

import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.RawMediaFrameEncoder;
import java.util.Objects;
import java.util.function.Consumer;

@FunctionalInterface
public interface RecordingPlaybackOutput {
    void onFrame(MediaFrame frame);

    default void onStateChanged(RecordingPlaybackState state) {
    }

    default void onError(Throwable failure) {
    }

    static RecordingPlaybackOutput binary(Consumer<byte[]> destination) {
        Objects.requireNonNull(destination, "destination");
        return frame -> destination.accept(RawMediaFrameEncoder.encode(frame));
    }
}
