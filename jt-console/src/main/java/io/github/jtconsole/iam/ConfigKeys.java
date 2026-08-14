package io.github.jtconsole.iam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可配置项的键目录。与权限码同理由代码定义：界面只能改值，不能造键——
 * 一个没有代码消费的配置键只会让人以为改了它会起作用。
 */
public final class ConfigKeys {

    public static final String PLATFORM_NAME = "platform.name";
    public static final String AMAP_KEY = "map.amap.key";
    public static final String AMAP_SECURITY_CODE = "map.amap.securityCode";
    public static final String MOVING_SPEED_THRESHOLD = "operations.movingSpeedKph";

    private static final List<ConfigKeyDefinition> CATALOG = List.of(
            new ConfigKeyDefinition(PLATFORM_NAME, "平台显示名称", "TEXT", false),
            new ConfigKeyDefinition(AMAP_KEY, "高德地图 Key", "TEXT", false),
            new ConfigKeyDefinition(AMAP_SECURITY_CODE, "高德地图安全密钥", "TEXT", true),
            new ConfigKeyDefinition(MOVING_SPEED_THRESHOLD, "行驶判定速度阈值（km/h）", "NUMBER", false));

    private static final Map<String, ConfigKeyDefinition> BY_KEY = index();

    private ConfigKeys() {
    }

    public static List<ConfigKeyDefinition> catalog() {
        return CATALOG;
    }

    public static boolean exists(String key) {
        return BY_KEY.containsKey(key);
    }

    public static boolean sensitive(String key) {
        ConfigKeyDefinition definition = BY_KEY.get(key);
        return definition != null && definition.sensitive();
    }

    private static Map<String, ConfigKeyDefinition> index() {
        Map<String, ConfigKeyDefinition> index = new LinkedHashMap<>();
        for (ConfigKeyDefinition definition : CATALOG) {
            index.put(definition.key(), definition);
        }
        return Map.copyOf(index);
    }

    /**
     * @param sensitive 为真时该值在列表接口与日志中脱敏展示，只有写入方知道原值
     */
    public record ConfigKeyDefinition(String key, String name, String type, boolean sensitive) {}
}
