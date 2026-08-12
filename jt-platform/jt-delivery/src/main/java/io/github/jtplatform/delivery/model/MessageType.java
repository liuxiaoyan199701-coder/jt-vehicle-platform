package io.github.jtplatform.delivery.model;

public enum MessageType {
    LOCATION("location", DeliveryReliability.BEST_EFFORT),
    HEARTBEAT("heartbeat", DeliveryReliability.BEST_EFFORT),
    REGISTER("register", DeliveryReliability.AT_LEAST_ONCE),
    AUTHENTICATION("authentication", DeliveryReliability.AT_LEAST_ONCE),
    ALARM("alarm", DeliveryReliability.AT_LEAST_ONCE),
    MULTIMEDIA("multimedia", DeliveryReliability.AT_LEAST_ONCE),
    RECORDING_METADATA("recording-metadata", DeliveryReliability.AT_LEAST_ONCE),
    TERMINAL_PARAMETER("terminal-parameter", DeliveryReliability.AT_LEAST_ONCE),
    CONTROL_RESULT("control-result", DeliveryReliability.AT_LEAST_ONCE),
    TRANSPARENT_DATA("transparent-data", DeliveryReliability.AT_LEAST_ONCE),
    OTHER("other", DeliveryReliability.AT_LEAST_ONCE);

    private final String wireValue;
    private final DeliveryReliability reliability;

    MessageType(String wireValue, DeliveryReliability reliability) {
        this.wireValue = wireValue;
        this.reliability = reliability;
    }

    public String wireValue() {
        return wireValue;
    }

    public DeliveryReliability reliability() {
        return reliability;
    }

    public boolean isCritical() {
        return reliability == DeliveryReliability.AT_LEAST_ONCE;
    }
}
