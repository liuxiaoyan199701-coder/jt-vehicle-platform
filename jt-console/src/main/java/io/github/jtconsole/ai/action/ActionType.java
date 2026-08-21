package io.github.jtconsole.ai.action;

import io.github.jtconsole.security.Permissions;
import java.util.Arrays;
import java.util.List;
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

    VEHICLE_CREATE("vehicle_create", "车辆建档", Permissions.VEHICLE_CREATE, false,
            List.of("deviceId", "plateNo"),
            List.of("plateColor", "brand", "channelCount", "remark", "tenantId", "departmentId")),
    VEHICLE_UPDATE("vehicle_update", "修改车辆档案", Permissions.VEHICLE_UPDATE, false,
            List.of("deviceId"),
            List.of("plateNo", "plateColor", "brand", "channelCount", "remark", "departmentId")),
    FLEET_CREATE("fleet_create", "创建车队", Permissions.FLEET_MANAGE, false,
            List.of("code", "name"),
            List.of("manager", "contactPhone", "remark", "tenantId")),
    FLEET_UPDATE("fleet_update", "修改车队", Permissions.FLEET_MANAGE, false,
            List.of("id"),
            List.of("code", "name", "manager", "contactPhone", "remark")),
    FLEET_MEMBERS("fleet_members", "调整车队成员", Permissions.FLEET_MANAGE, false,
            List.of("id", "deviceIds"), List.of()),
    GEOFENCE_CREATE("geofence_create", "创建电子围栏", Permissions.GEOFENCE_MANAGE, false,
            List.of("name"),
            List.of("shape", "centerGcjLat", "centerGcjLng", "radiusMeters", "points",
                    "color", "enabled", "alertOnEnter", "alertOnExit", "speedLimitKph",
                    "vehicleIds", "tenantId")),
    GEOFENCE_UPDATE("geofence_update", "修改电子围栏", Permissions.GEOFENCE_MANAGE, false,
            List.of("id"),
            List.of("name", "shape", "centerGcjLat", "centerGcjLng", "radiusMeters", "points",
                    "color", "enabled", "alertOnEnter", "alertOnExit", "speedLimitKph", "vehicleIds")),
    ALARM_ACKNOWLEDGE("alarm_acknowledge", "确认告警", Permissions.ALARM_HANDLE, false,
            List.of("alarmId"), List.of("note")),
    ALARM_CLOSE("alarm_close", "关闭告警", Permissions.ALARM_HANDLE, false,
            List.of("alarmId"), List.of("note")),
    RULE_CREATE("rule_create", "创建告警规则", Permissions.RULE_MANAGE, false,
            List.of("name", "type", "thresholdKph"),
            List.of("durationMinutes", "level", "enabled", "vehicleIds", "tenantId")),
    RULE_UPDATE("rule_update", "修改告警规则", Permissions.RULE_MANAGE, false,
            List.of("id"),
            List.of("name", "type", "thresholdKph", "durationMinutes", "level", "enabled", "vehicleIds")),
    RULE_DELETE("rule_delete", "删除告警规则", Permissions.RULE_MANAGE, true,
            List.of("id"), List.of()),

    // ---- 不可逆或影响运行：永远需要确认 ----

    /** 删掉的车辆档案要从备份找回，而备份未必有昨天那一份。 */
    VEHICLE_DELETE("vehicle_delete", "删除车辆档案", Permissions.VEHICLE_DELETE, true,
            List.of("deviceId"), List.of()),
    FLEET_DELETE("fleet_delete", "删除车队", Permissions.FLEET_MANAGE, true,
            List.of("id"), List.of()),
    GEOFENCE_DELETE("geofence_delete", "删除电子围栏", Permissions.GEOFENCE_MANAGE, true,
            List.of("id"), List.of()),
    /** 指令一旦发出就到了路上那台车的屏幕上，撤不回来。 */
    SEND_TEXT("send_text", "向终端下发文本消息", Permissions.COMMAND_SEND, true,
            List.of("deviceId", "text"), List.of()),
    /**
     * 让车现在拍一张。
     *
     * <p>需要确认的理由与 {@link #SEND_TEXT} 同源：它会通过网关向路上那台车下发 0x8801，
     * 唤醒摄像头、占用车载上行带宽，还会在终端留下一次动作。查看**已经拍好的**照片是只读的，
     * 走 {@code query_photos} 工具，不经过这里。
     *
     * <p><b>通道参数叫 {@code channel} 而不是 {@code channelId}</b>，与
     * {@code photo_gallery}、{@code live_video} 两个视图保持同一个词。线上实测过代价：
     * 模型刚用 {@code channel} 查完抓拍，紧接着提议拍照时自然沿用同一个词，被拒之后当着用户的面
     * 说了一句「参数名写错了，重新提交」。同一个概念在模型面前只能有一个名字。
     * 指令接口那边仍叫 {@code channelId}，由前端的路由白名单负责转译。
     */
    TAKE_PHOTO("take_photo", "让终端立即拍照", Permissions.COMMAND_SEND, true,
            List.of("deviceId"), List.of("channel", "count", "resolution")),
    /** 下发升级包会改写路上那台车的固件，且可能致其短时不可用，永远需要确认。 */
    UPGRADE("upgrade", "下发升级包", Permissions.COMMAND_SEND, true,
            List.of("deviceId", "packageId"), List.of()),

    // ---- 平台级：只有平台管理员可见 ----

    TENANT_CREATE("tenant_create", "开通租户", Permissions.PLATFORM_TENANT_MANAGE, false,
            List.of("code", "name"),
            List.of("planId", "contactName", "contactPhone", "remark")),
    TENANT_UPDATE("tenant_update", "修改租户信息", Permissions.PLATFORM_TENANT_MANAGE, false,
            List.of("id"),
            List.of("name", "planId", "contactName", "contactPhone", "remark")),
    /** 停用会让该租户的一整批用户当场登不进来。 */
    TENANT_DISABLE("tenant_disable", "停用租户", Permissions.PLATFORM_TENANT_MANAGE, true,
            List.of("id"), List.of()),
    PLAN_CREATE("plan_create", "创建套餐", Permissions.PLATFORM_PLAN_MANAGE, false,
            List.of("name"),
            List.of("maxVehicles", "maxAccounts", "maxAiCallsMonthly", "priceCents",
                    "periodMonths", "remark")),
    /** 改套餐会立刻改变一批租户的配额上限。 */
    PLAN_UPDATE("plan_update", "修改套餐", Permissions.PLATFORM_PLAN_MANAGE, true,
            List.of("id", "name"),
            List.of("maxVehicles", "maxAccounts", "maxAiCallsMonthly", "priceCents",
                    "periodMonths", "enabled", "remark"));

    private final String wireName;
    private final String label;
    private final String requiredPermission;
    private final boolean alwaysConfirm;
    private final List<String> requiredFields;
    private final List<String> optionalFields;

    ActionType(
            String wireName, String label, String requiredPermission, boolean alwaysConfirm,
            List<String> requiredFields, List<String> optionalFields) {
        this.wireName = wireName;
        this.label = label;
        this.requiredPermission = requiredPermission;
        this.alwaysConfirm = alwaysConfirm;
        this.requiredFields = List.copyOf(requiredFields);
        this.optionalFields = List.copyOf(optionalFields);
    }

    /**
     * 必填字段，字段名与既有 REST 接口完全一致。
     *
     * <p>必须把字段名明确告诉模型：它不知道接口长什么样时会自己发明一套（把 plateNo 写成 plate、
     * channelCount 写成 cameraCount），生成的确认卡片看着没问题，用户一点就失败。
     *
     * <p>只给名字还不够——它会把 tenantId 填成用户名「admin」、把 plateColor 填成英文 "blue"。
     * 所以 {@link #fieldHint(String)} 另外给出类型与取值范围。
     */
    public List<String> requiredFields() {
        return requiredFields;
    }

    /** 字段的类型与取值说明，拼进提示词。没有特别说明的字段返回空串。 */
    public static String fieldHint(String field) {
        return switch (field) {
            case "tenantId" -> "（数字 id，不是租户名；不确定就省略，平台会按当前用户归属处理）";
            case "departmentId", "planId", "id" -> "（数字 id，必须来自查询结果）";
            case "plateColor" -> "（中文：蓝色/黄色/白色/绿色/黑色）";
            case "channelCount" -> "（数字，摄像头路数，默认 1）";
            case "alarmId" -> "（数字，来自告警查询结果）";
            case "packageId" -> "（数字，来自升级包查询结果）";
            case "deviceIds", "vehicleIds" -> "（设备号字符串数组）";
            case "centerGcjLat", "centerGcjLng" -> "（数字，GCJ-02 坐标；圆形围栏必填）";
            case "radiusMeters", "speedLimitKph" -> "（数字；radiusMeters 为圆形半径或路线走廊半宽）";
            case "shape" -> "（circle 圆形（默认）/ rectangle 矩形 / polygon 多边形 / route 路线）";
            case "points" -> "（GCJ-02 坐标数组 [[lat,lng],...]；矩形 2 个对角点、多边形至少 3 个顶点、路线至少 2 个途经点）";
            case "enabled", "alertOnEnter", "alertOnExit" -> "（true/false）";
            case "type" -> "（SPEED_LIMIT 超速阈值 / IDLE_TIMEOUT 怠速超时 / FATIGUE_DRIVING 疲劳驾驶）";
            case "thresholdKph" -> "（数字，km/h；超速为下限、怠速为低速上界、疲劳为高速下界）";
            case "durationMinutes" -> "（数字，分钟；怠速/疲劳的持续时长，超速规则填 0）";
            case "level" -> "（LOW / MEDIUM / HIGH / CRITICAL）";
            case "code" -> "（英文或数字编码，全局唯一）";
            default -> "";
        };
    }

    public List<String> optionalFields() {
        return optionalFields;
    }

    public boolean knows(String field) {
        return requiredFields.contains(field) || optionalFields.contains(field);
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
