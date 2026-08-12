package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.diagnostics.LogEntry;
import io.github.jtplatform.simulator.signal.SignalState;

public interface RuntimeListener {
    RuntimeListener NOOP = new RuntimeListener() { };

    default void onSignalState(SignalState state, String detail) {
    }

    default void onMediaState(MediaViewState state) {
    }

    default void onPreviewFrame(byte[] jpeg) {
    }

    default void onLog(LogEntry entry) {
    }

    default void onError(String context, Throwable error) {
    }
}
