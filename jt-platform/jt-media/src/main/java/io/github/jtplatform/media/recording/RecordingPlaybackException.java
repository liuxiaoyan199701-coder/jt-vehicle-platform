package io.github.jtplatform.media.recording;

import java.io.IOException;

public final class RecordingPlaybackException extends IOException {
    public RecordingPlaybackException(String message) {
        super(message);
    }

    public RecordingPlaybackException(String message, Throwable cause) {
        super(message, cause);
    }
}
