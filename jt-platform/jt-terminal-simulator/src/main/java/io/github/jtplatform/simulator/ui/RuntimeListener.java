package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.diagnostics.LogEntry;
import io.github.jtplatform.simulator.signal.SignalState;
import io.github.jtplatform.simulator.trip.TripViewState;

public interface RuntimeListener {
    RuntimeListener NOOP = new RuntimeListener() { };

    default void onSignalState(SignalState state, String detail) {
    }

    default void onMediaState(MediaViewState state) {
    }

    /** 带默认实现，以免既有实现者被这个新增回调打断编译。 */
    default void onTripState(TripViewState state) {
    }

    default void onPreviewFrame(byte[] jpeg) {
    }

    default void onLog(LogEntry entry) {
    }

    default void onError(String context, Throwable error) {
    }
}
