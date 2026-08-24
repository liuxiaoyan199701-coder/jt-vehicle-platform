package io.github.jtplatform.delivery.model;

public enum MessageType {
    LOCATION("location", DeliveryReliability.BEST_EFFORT),
    /**
     * 0x0704 定位数据批量上传。
     *
     * <p>与实时位置相反，必须可靠投递：批量补传是那段轨迹的<b>唯一</b>一份副本，丢了就永远补不回来，
     * 而实时位置几十秒后就有下一个。更要命的是它到达的时机——设备刚恢复联通时集中上传，正是
     * 整条投递通道最拥挤的时刻，尽力而为的报文恰好在这时被优先丢弃。
     */
    BATCH_LOCATION("batch-location", DeliveryReliability.AT_LEAST_ONCE),
    HEARTBEAT("heartbeat", DeliveryReliability.BEST_EFFORT),
    REGISTER("register", DeliveryReliability.AT_LEAST_ONCE),
    AUTHENTICATION("authentication", DeliveryReliability.AT_LEAST_ONCE),
    ALARM("alarm", DeliveryReliability.AT_LEAST_ONCE),
    MULTIMEDIA("multimedia", DeliveryReliability.AT_LEAST_ONCE),
    RECORDING_METADATA("recording-metadata", DeliveryReliability.AT_LEAST_ONCE),
    WAYBILL("waybill", DeliveryReliability.AT_LEAST_ONCE),
    TERMINAL_PARAMETER("terminal-parameter", DeliveryReliability.AT_LEAST_ONCE),
    CONTROL_RESULT("control-result", DeliveryReliability.AT_LEAST_ONCE),
    CONNECTION("connection", DeliveryReliability.AT_LEAST_ONCE),
    /**
     * 设备报文日志（上行/下行原始帧 + 解析结果）。
     *
     * <p>与 {@code LOCATION} 同一量级甚至更高——每条被记录的报文都会发一个信封。走
     * {@code AT_LEAST_ONCE} 会在控制台停机时把落盘 spool 打爆，用磁盘换几条排障日志不划算：
     * 日志丢几条可容忍，主链路与磁盘不可。
     */
    DEVICE_LOG("device_log", DeliveryReliability.BEST_EFFORT),
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
