package io.github.jtplatform.simulator.signal;

public enum SignalState {
    DISCONNECTED,
    CONNECTING,
    REGISTERING,
    AUTHENTICATING,
    ONLINE,
    RECONNECT_WAIT,
    STOPPING,
    FAILED
}
