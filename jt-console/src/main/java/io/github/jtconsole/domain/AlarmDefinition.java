package io.github.jtconsole.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** JT/T 808 报警位到运营告警口径的稳定白名单。 */
public record AlarmDefinition(String type, String title, AlarmLevel level) {

    private static final Map<String, AlarmDefinition> PROTOCOL = definitions();

    public static Optional<AlarmDefinition> protocol(String key) {
        return Optional.ofNullable(PROTOCOL.get(key));
    }

    public static Map<String, AlarmDefinition> protocolDefinitions() {
        return PROTOCOL;
    }

    public static AlarmDefinition geofenceEnter() {
        return new AlarmDefinition("geofenceEnter", "进入电子围栏", AlarmLevel.MEDIUM);
    }

    public static AlarmDefinition geofenceExit() {
        return new AlarmDefinition("geofenceExit", "离开电子围栏", AlarmLevel.MEDIUM);
    }

    public static AlarmDefinition geofenceOverspeed() {
        return new AlarmDefinition("geofenceOverspeed", "围栏内超速", AlarmLevel.HIGH);
    }

    public static AlarmDefinition ruleSpeedLimit(String title, AlarmLevel level) {
        return new AlarmDefinition("ruleSpeedLimit", title, level);
    }

    public static AlarmDefinition ruleIdleTimeout(String title, AlarmLevel level) {
        return new AlarmDefinition("ruleIdleTimeout", title, level);
    }

    public static AlarmDefinition ruleFatigueDriving(String title, AlarmLevel level) {
        return new AlarmDefinition("ruleFatigueDriving", title, level);
    }

    private static Map<String, AlarmDefinition> definitions() {
        Map<String, AlarmDefinition> values = new LinkedHashMap<>();
        add(values, "emergency", "紧急报警", AlarmLevel.CRITICAL);
        add(values, "overspeed", "超速报警", AlarmLevel.MEDIUM);
        add(values, "fatigueDriving", "疲劳驾驶", AlarmLevel.HIGH);
        add(values, "dangerousDrivingBehavior", "危险驾驶行为", AlarmLevel.HIGH);
        add(values, "gnssModuleFault", "GNSS 模块故障", AlarmLevel.MEDIUM);
        add(values, "gnssAntennaDisconnected", "GNSS 天线断开", AlarmLevel.MEDIUM);
        add(values, "gnssAntennaShortCircuit", "GNSS 天线短路", AlarmLevel.MEDIUM);
        add(values, "terminalMainPowerUndervoltage", "终端主电源欠压", AlarmLevel.MEDIUM);
        add(values, "terminalMainPowerFailure", "终端主电源掉电", AlarmLevel.HIGH);
        add(values, "terminalDisplayFault", "终端显示器故障", AlarmLevel.LOW);
        add(values, "textToSpeechFault", "语音合成模块故障", AlarmLevel.LOW);
        add(values, "cameraFault", "摄像头故障", AlarmLevel.LOW);
        add(values, "icCardModuleFault", "道路运输证 IC 卡模块故障", AlarmLevel.LOW);
        add(values, "overspeedWarning", "超速预警", AlarmLevel.LOW);
        add(values, "fatigueWarning", "疲劳驾驶预警", AlarmLevel.LOW);
        add(values, "drivingOvertime", "当天累计驾驶超时", AlarmLevel.MEDIUM);
        add(values, "parkingOvertime", "超时停车", AlarmLevel.LOW);
        add(values, "areaEntryExit", "进出区域", AlarmLevel.MEDIUM);
        add(values, "routeEntryExit", "进出路线", AlarmLevel.MEDIUM);
        add(values, "routeTravelTime", "路段行驶时间异常", AlarmLevel.MEDIUM);
        add(values, "routeDeviation", "路线偏离", AlarmLevel.MEDIUM);
        add(values, "vehicleVssFault", "车辆 VSS 故障", AlarmLevel.MEDIUM);
        add(values, "fuelAbnormal", "车辆油量异常", AlarmLevel.MEDIUM);
        add(values, "vehicleTheft", "车辆被盗", AlarmLevel.HIGH);
        add(values, "illegalIgnition", "车辆非法点火", AlarmLevel.HIGH);
        add(values, "illegalDisplacement", "车辆非法位移", AlarmLevel.HIGH);
        add(values, "collisionRollover", "碰撞侧翻报警", AlarmLevel.CRITICAL);
        add(values, "rolloverWarning", "侧翻预警", AlarmLevel.HIGH);
        return Map.copyOf(values);
    }

    private static void add(
            Map<String, AlarmDefinition> values, String type, String title, AlarmLevel level) {
        values.put(type, new AlarmDefinition(type, title, level));
    }
}
