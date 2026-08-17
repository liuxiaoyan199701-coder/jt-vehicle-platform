package io.github.jtconsole.ai.action;

import io.github.jtconsole.security.Permissions;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 可以提议的动作。这是一份**白名单**——不在其中的一律拒绝，而不是「除了危险的都放行」。
 *
 * <p>每一项声明三件事：需要什么权限、前端该调哪个既有接口、能否被配置成免确认。
 *
 * <p>权限码复用既有定义，不新建映射：角色配置改了，AI 能做什么自动跟着改，不需要有人记得
 * 同步维护第二份清单。
 *
 * <p>{@link #alwaysConfirm} 为 true 的动作**无视任何配置**，永远需要用户点确认。这不是保守，
 * 是因为它们要么撤不回来（删除、已经发到路上那台车的指令），要么会当场影响一批人（租户停用、
 * 换套餐）。一个能被配置关掉全部安全检查的设计等于没有安全设计。
 */
public enum ActionType {

    // ---- 可逆的创建与修改：允许配置为免确认 ----

    VEHICLE_CREATE("vehicle_create", "车辆建档", Permissions.VEHICLE_CREATE, false),
    VEHICLE_UPDATE("vehicle_update", "修改车辆档案", Permissions.VEHICLE_UPDATE, false),
    FLEET_CREATE("fleet_create", "创建车队", Permissions.FLEET_MANAGE, false),
    FLEET_UPDATE("fleet_update", "修改车队", Permissions.FLEET_MANAGE, false),
    FLEET_MEMBERS("fleet_members", "调整车队成员", Permissions.FLEET_MANAGE, false),
    GEOFENCE_CREATE("geofence_create", "创建电子围栏", Permissions.GEOFENCE_MANAGE, false),
    GEOFENCE_UPDATE("geofence_update", "修改电子围栏", Permissions.GEOFENCE_MANAGE, false),
    ALARM_ACKNOWLEDGE("alarm_acknowledge", "确认告警", Permissions.ALARM_HANDLE, false),
    ALARM_CLOSE("alarm_close", "关闭告警", Permissions.ALARM_HANDLE, false),

    // ---- 不可逆或影响运行：永远需要确认 ----

    /** 删掉的车辆档案要从备份找回，而备份未必有昨天那一份。 */
    VEHICLE_DELETE("vehicle_delete", "删除车辆档案", Permissions.VEHICLE_DELETE, true),
    FLEET_DELETE("fleet_delete", "删除车队", Permissions.FLEET_MANAGE, true),
    GEOFENCE_DELETE("geofence_delete", "删除电子围栏", Permissions.GEOFENCE_MANAGE, true),
    /** 指令一旦发出就到了路上那台车的屏幕上，撤不回来。 */
    SEND_TEXT("send_text", "向终端下发文本消息", Permissions.COMMAND_SEND, true),

    // ---- 平台级：只有平台管理员可见 ----

    TENANT_CREATE("tenant_create", "开通租户", Permissions.PLATFORM_TENANT_MANAGE, false),
    TENANT_UPDATE("tenant_update", "修改租户信息", Permissions.PLATFORM_TENANT_MANAGE, false),
    /** 停用会让该租户的一整批用户当场登不进来。 */
    TENANT_DISABLE("tenant_disable", "停用租户", Permissions.PLATFORM_TENANT_MANAGE, true),
    PLAN_CREATE("plan_create", "创建套餐", Permissions.PLATFORM_PLAN_MANAGE, false),
    /** 改套餐会立刻改变一批租户的配额上限。 */
    PLAN_UPDATE("plan_update", "修改套餐", Permissions.PLATFORM_PLAN_MANAGE, true);

    private final String wireName;
    private final String label;
    private final String requiredPermission;
    private final boolean alwaysConfirm;

    ActionType(String wireName, String label, String requiredPermission, boolean alwaysConfirm) {
        this.wireName = wireName;
        this.label = label;
        this.requiredPermission = requiredPermission;
        this.alwaysConfirm = alwaysConfirm;
    }

    public String wireName() {
        return wireName;
    }

    public String label() {
        return label;
    }

    public String requiredPermission() {
        return requiredPermission;
    }

    /** 是否无视配置、永远需要人工确认。 */
    public boolean alwaysConfirm() {
        return alwaysConfirm;
    }

    public static Optional<ActionType> of(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return Optional.empty();
        }
        String needle = wireName.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(needle))
                .findFirst();
    }
}
