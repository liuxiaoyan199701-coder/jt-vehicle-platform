package io.github.jtplatform.simulator.stream;

public interface MediaListener {
    MediaListener NOOP = new MediaListener() { };

    default void onStateChanged(MediaSnapshot snapshot) {
    }

    default void onPreviewFrame(byte[] jpeg) {
    }

    default void onDiagnostic(String message) {
    }

    default void onError(String context, Throwable error) {
    }
}
