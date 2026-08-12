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
}
