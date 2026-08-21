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

    default void onLocationReported(java.time.Instant timestamp) {
    }

    default void onDriverAction(String detail) {
    }

    default void onRecordingEvent(String detail) {
    }

    default void onBlindspotEvent(String detail) {
    }

    default void onTerminalParametersChanged(java.util.Map<Integer, Object> parameters) {
    }

    default void onTerminalManagementEvent(String detail) {
    }

    default void onUpgradeEvent(String detail) {
    }

    default void onTerminalText(String content, boolean urgent) {
    }

    default void onFailNextUpgradeConsumed() {
    }

    default void onFleetState(io.github.jtplatform.simulator.signal.FleetRuntime.FleetMemberState state) {
    }
}
