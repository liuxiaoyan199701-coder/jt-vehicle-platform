package io.github.jtplatform.simulator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/** 终端管理相关的持久化状态：参数表和 OTA 模拟开关。 */
public record TerminalManagementConfig(
        Map<Integer, Object> parameters,
        int upgradeInstallDelayMillis,
        boolean failNextUpgrade) {

    public static final int DEFAULT_UPGRADE_INSTALL_DELAY_MILLIS = 1_000;
    public static final int MAX_UPGRADE_INSTALL_DELAY_MILLIS = 60_000;
    public static final int HEARTBEAT_INTERVAL_PARAMETER = 0x0001;

    public TerminalManagementConfig {
        Map<Integer, Object> normalized = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (key != null && value != null) {
                    normalized.put(key, normalizeValue(key, value));
                }
            });
        }
        if (!normalized.containsKey(HEARTBEAT_INTERVAL_PARAMETER)) {
            normalized.put(HEARTBEAT_INTERVAL_PARAMETER, 30L);
        }
        parameters = Map.copyOf(normalized);
        if (upgradeInstallDelayMillis < 0
                || upgradeInstallDelayMillis > MAX_UPGRADE_INSTALL_DELAY_MILLIS) {
            throw new IllegalArgumentException("upgradeInstallDelayMillis must be in range 0.."
                    + MAX_UPGRADE_INSTALL_DELAY_MILLIS);
        }
    }

    /** 兼容尚未包含终端管理字段的旧配置。 */
    public TerminalManagementConfig(Map<Integer, Object> parameters) {
        this(parameters, DEFAULT_UPGRADE_INSTALL_DELAY_MILLIS, false);
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static TerminalManagementConfig fromJson(
            @JsonProperty("parameters") Map<Integer, Object> parameters,
            @JsonProperty("upgradeInstallDelayMillis") Integer upgradeInstallDelayMillis,
            @JsonProperty("failNextUpgrade") Boolean failNextUpgrade) {
        return new TerminalManagementConfig(
                parameters,
                upgradeInstallDelayMillis == null
                        ? DEFAULT_UPGRADE_INSTALL_DELAY_MILLIS : upgradeInstallDelayMillis,
                failNextUpgrade != null && failNextUpgrade);
    }

    public static TerminalManagementConfig defaults() {
        return new TerminalManagementConfig(
                Map.of(
                        0x0001, 30L, // 终端心跳发送间隔，秒
                        0x0002, 10L, // TCP 应答超时，秒
                        0x0003, 3L,  // TCP 重传次数
                        0x0055, 80L, // 最高速度，km/h
                        0x0064, 0L,  // 定时拍照参数
                        0x0065, 0L), // 定距拍照参数
                DEFAULT_UPGRADE_INSTALL_DELAY_MILLIS,
                false);
    }

    private static Object normalizeValue(int key, Object value) {
        if (!(value instanceof Number number)) {
            return value;
        }
        return switch (key) {
            // ParameterConverter 中这些参数按 BYTE/WORD 编码，其余常用数值参数按 DWORD。
            case 0x0031, 0x005B, 0x005C, 0x005D, 0x005E,
                    0x0081, 0x0082, 0x0084,
                    0x0090, 0x0091, 0x0092, 0x0094,
                    0x0101, 0x0103, 0xF370 -> number.intValue();
            default -> number.longValue();
        };
    }

    public TerminalManagementConfig withParameters(Map<Integer, Object> nextParameters) {
        return new TerminalManagementConfig(nextParameters, upgradeInstallDelayMillis, failNextUpgrade);
    }

    public TerminalManagementConfig withFailNextUpgrade(boolean value) {
        return new TerminalManagementConfig(parameters, upgradeInstallDelayMillis, value);
    }

    public TerminalManagementConfig withUpgradeInstallDelayMillis(int value) {
        return new TerminalManagementConfig(parameters, value, failNextUpgrade);
    }
}
