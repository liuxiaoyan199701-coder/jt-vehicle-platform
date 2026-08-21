package io.github.jtconsole.security;

import io.github.jtconsole.domain.PermissionDefinition;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限码目录。权限点必须与代码里的检查点一一对应，因此定义在代码中而非数据库：
 * 一个没有代码消费的权限码只会在界面上产生「勾了却不起作用」的假象。
 *
 * <p>启动时同步进 {@code permission} 表；代码中删除的权限码在同步时被标记为失效，
 * 不再参与授权判定，但保留行以便历史角色配置可追溯。
 */
public final class Permissions {

    public static final String DASHBOARD_VIEW = "dashboard:view";
    public static final String MONITOR_VIEW = "monitor:view";
    public static final String TRACK_VIEW = "track:view";

    public static final String VEHICLE_LIST = "vehicle:list";
    public static final String VEHICLE_CREATE = "vehicle:create";
    public static final String VEHICLE_UPDATE = "vehicle:update";
    public static final String VEHICLE_DELETE = "vehicle:delete";

    public static final String FLEET_LIST = "fleet:list";
    public static final String FLEET_MANAGE = "fleet:manage";

    public static final String GEOFENCE_LIST = "geofence:list";
    public static final String GEOFENCE_MANAGE = "geofence:manage";

    public static final String ALARM_LIST = "alarm:list";
    public static final String ALARM_HANDLE = "alarm:handle";
    public static final String RULE_LIST = "alarm-rule:list";
    public static final String RULE_MANAGE = "alarm-rule:manage";

    public static final String MEDIA_LIST = "media:list";
    public static final String COMMAND_SEND = "command:send";
    public static final String VIDEO_PLAY = "video:play";
    public static final String RECORDING_SEARCH = "recording:search";
    public static final String RECORDING_PLAYBACK = "recording:playback";

    /**
     * 使用 AI 助手。刻意登记为写权限：对话会消耗租户的月度调用配额，是一种状态变更；
     * 连带的后果是只读账号（全局禁非 GET）用不了对话——这是有意的，见 {@link #TENANT_VIEWER_PERMISSIONS}。
     */
    public static final String AI_CHAT = "ai:chat";
    public static final String AI_REPORT_VIEW = "ai:report:view";

    public static final String SYSTEM_ACCOUNT_LIST = "system:account:list";
    public static final String SYSTEM_ACCOUNT_MANAGE = "system:account:manage";
    public static final String SYSTEM_ROLE_LIST = "system:role:list";
    public static final String SYSTEM_ROLE_MANAGE = "system:role:manage";
    public static final String SYSTEM_DEPT_LIST = "system:dept:list";
    public static final String SYSTEM_DEPT_MANAGE = "system:dept:manage";
    public static final String SYSTEM_POSITION_LIST = "system:position:list";
    public static final String SYSTEM_POSITION_MANAGE = "system:position:manage";
    public static final String SYSTEM_CONFIG_VIEW = "system:config:view";
    public static final String SYSTEM_CONFIG_MANAGE = "system:config:manage";
    public static final String SYSTEM_AUDIT_VIEW = "system:audit:view";

    public static final String PLATFORM_TENANT_MANAGE = "platform:tenant:manage";
    public static final String PLATFORM_PLAN_MANAGE = "platform:plan:manage";
    public static final String PLATFORM_REGISTRATION_REVIEW = "platform:registration:review";
    public static final String PLATFORM_CONFIG_MANAGE = "platform:config:manage";

    private static final List<PermissionDefinition> CATALOG = List.of(
            read("dashboard", DASHBOARD_VIEW, "查看运营看板", 1),
            read("monitor", MONITOR_VIEW, "查看实时监控", 1),
            read("track", TRACK_VIEW, "查看轨迹回放", 1),

            read("vehicle", VEHICLE_LIST, "查看车辆档案", 1),
            write("vehicle", VEHICLE_CREATE, "新增车辆建档", 2),
            write("vehicle", VEHICLE_UPDATE, "编辑车辆档案", 3),
            write("vehicle", VEHICLE_DELETE, "删除车辆档案", 4),

            read("fleet", FLEET_LIST, "查看车队", 1),
            write("fleet", FLEET_MANAGE, "管理车队与成员", 2),

            read("geofence", GEOFENCE_LIST, "查看电子围栏", 1),
            write("geofence", GEOFENCE_MANAGE, "管理电子围栏", 2),

            read("alarm", ALARM_LIST, "查看告警", 1),
            write("alarm", ALARM_HANDLE, "确认与关闭告警", 2),
            read("alarm-rule", RULE_LIST, "查看告警规则", 3),
            write("alarm-rule", RULE_MANAGE, "管理告警规则", 4),

            read("media", MEDIA_LIST, "查看多媒体文件", 1),
            write("command", COMMAND_SEND, "下发终端指令", 1),
            write("video", VIDEO_PLAY, "播放实时视频", 1),
            read("recording", RECORDING_SEARCH, "检索录像", 1),
            write("recording", RECORDING_PLAYBACK, "回放录像", 2),

            write("ai", AI_CHAT, "使用 AI 助手", 1),
            read("ai", AI_REPORT_VIEW, "查看运营简报", 2),

            read("system", SYSTEM_ACCOUNT_LIST, "查看账号", 1),
            write("system", SYSTEM_ACCOUNT_MANAGE, "管理账号", 2),
            read("system", SYSTEM_ROLE_LIST, "查看角色", 3),
            write("system", SYSTEM_ROLE_MANAGE, "管理角色", 4),
            read("system", SYSTEM_DEPT_LIST, "查看部门", 5),
            write("system", SYSTEM_DEPT_MANAGE, "管理部门", 6),
            read("system", SYSTEM_POSITION_LIST, "查看岗位", 7),
            write("system", SYSTEM_POSITION_MANAGE, "管理岗位", 8),
            read("system", SYSTEM_CONFIG_VIEW, "查看租户配置", 9),
            write("system", SYSTEM_CONFIG_MANAGE, "管理租户配置", 10),
            read("system", SYSTEM_AUDIT_VIEW, "查看审计日志", 11),

            platformWrite(PLATFORM_TENANT_MANAGE, "管理租户", 1),
            platformWrite(PLATFORM_PLAN_MANAGE, "管理套餐与续费", 2),
            platformWrite(PLATFORM_REGISTRATION_REVIEW, "审批注册申请", 3),
            platformWrite(PLATFORM_CONFIG_MANAGE, "管理全局默认配置", 4));

    private static final Map<String, PermissionDefinition> BY_CODE = indexByCode();

    /** 平台管理员：全部权限，含平台级。 */
    public static final Set<String> PLATFORM_ADMIN_PERMISSIONS = allCodes();

    /** 租户管理员：本租户全部业务与系统管理，不含平台级。 */
    public static final Set<String> TENANT_ADMIN_PERMISSIONS = tenantScoped();

    /** 租户操作员：全部业务能力，不含账号/角色/部门/岗位/配置管理与审计。 */
    public static final Set<String> TENANT_OPERATOR_PERMISSIONS = Set.of(
            DASHBOARD_VIEW, MONITOR_VIEW, TRACK_VIEW,
            VEHICLE_LIST, VEHICLE_CREATE, VEHICLE_UPDATE, VEHICLE_DELETE,
            FLEET_LIST, FLEET_MANAGE,
            GEOFENCE_LIST, GEOFENCE_MANAGE,
            ALARM_LIST, ALARM_HANDLE,
            RULE_LIST, RULE_MANAGE,
            MEDIA_LIST, COMMAND_SEND, VIDEO_PLAY,
            RECORDING_SEARCH, RECORDING_PLAYBACK,
            AI_CHAT, AI_REPORT_VIEW);

    /**
     * 租户只读：仅查看。不含 {@link #VIDEO_PLAY}——开流是 POST 且会驱动设备推流，不属于「查看」。
     *
     * <p>同理不含 {@link #AI_CHAT}：对话是 POST 且消耗租户配额，而只读账号被全局禁非 GET，
     * 给了也用不了；简报是纯查询，正常开放。
     */
    public static final Set<String> TENANT_VIEWER_PERMISSIONS = Set.of(
            DASHBOARD_VIEW, MONITOR_VIEW, TRACK_VIEW,
            VEHICLE_LIST, FLEET_LIST, GEOFENCE_LIST, ALARM_LIST, MEDIA_LIST,
            RULE_LIST, RECORDING_SEARCH,
            AI_REPORT_VIEW);

    private Permissions() {
    }

    public static List<PermissionDefinition> catalog() {
        return CATALOG;
    }

    public static boolean exists(String code) {
        return BY_CODE.containsKey(code);
    }

    public static boolean isPlatformOnly(String code) {
        PermissionDefinition definition = BY_CODE.get(code);
        return definition != null && definition.platformOnly();
    }

    public static boolean isWrite(String code) {
        PermissionDefinition definition = BY_CODE.get(code);
        return definition != null && definition.write();
    }

    /** 判断权限集合是否完全不含写权限，用于安全层集中拒绝只读账号的非 GET 请求。 */
    public static boolean readOnly(Set<String> codes) {
        return codes.stream().noneMatch(Permissions::isWrite);
    }

    private static PermissionDefinition read(String module, String code, String name, int order) {
        return new PermissionDefinition(code, module, name, false, false, order);
    }

    private static PermissionDefinition write(String module, String code, String name, int order) {
        return new PermissionDefinition(code, module, name, false, true, order);
    }

    private static PermissionDefinition platformWrite(String code, String name, int order) {
        return new PermissionDefinition(code, "platform", name, true, true, order);
    }

    private static Map<String, PermissionDefinition> indexByCode() {
        Map<String, PermissionDefinition> index = new LinkedHashMap<>();
        for (PermissionDefinition definition : CATALOG) {
            if (index.put(definition.code(), definition) != null) {
                throw new IllegalStateException("权限码重复定义：" + definition.code());
            }
        }
        return Map.copyOf(index);
    }

    private static Set<String> allCodes() {
        return Set.copyOf(new LinkedHashSet<>(CATALOG.stream()
                .map(PermissionDefinition::code)
                .toList()));
    }

    private static Set<String> tenantScoped() {
        return Set.copyOf(new LinkedHashSet<>(CATALOG.stream()
                .filter(definition -> !definition.platformOnly())
                .map(PermissionDefinition::code)
                .toList()));
    }
}
