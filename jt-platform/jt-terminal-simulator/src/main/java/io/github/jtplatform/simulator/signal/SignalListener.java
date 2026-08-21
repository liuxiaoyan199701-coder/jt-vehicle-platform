package io.github.jtplatform.simulator.signal;

import org.yzh.protocol.basics.JTMessage;

public interface SignalListener {
    SignalListener NOOP = new SignalListener() {};

    default void onStateChanged(SignalState previous, SignalState current, String detail) {
    }

    default void onMessageReceived(JTMessage message) {
    }

    default void onError(String context, Throwable error) {
    }

    /** 非致命诊断输出（如 FFmpeg stderr 行），用于界面日志面板 */
    default void onDiagnostic(String message) {
    }

    default void onLocationReported(java.time.Instant timestamp) {
    }

    default void onLocationFix(LocationFix fix) {
    }

    default void onRecordingEvent(String detail) {
    }
}
