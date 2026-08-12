package io.github.jtplatform.media.recording;

import java.io.IOException;

public final class RecordingExportException extends IOException {
    public RecordingExportException(String message) {
        super(message);
    }

    public RecordingExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
