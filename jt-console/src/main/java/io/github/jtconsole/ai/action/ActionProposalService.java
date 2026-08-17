package io.github.jtconsole.ai.action;

import io.github.jtconsole.ai.tool.ToolSession;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 校验并构造动作提议。
 *
 * <p>三道闸门，缺一不可，且顺序有意义：
 * <ol>
 *   <li><b>动作在白名单内</b>——不在其中的一律拒绝，而不是「除了危险的都放行」。</li>
 *   <li><b>发起人持有该动作的权限</b>——用既有权限码判定，不新建映射。</li>
 *   <li><b>目标资源在发起人的数据范围内可见</b>——权限说明「能不能做这类事」，
 *       数据范围说明「能对哪些对象做」，两者都过才放行。</li>
 * </ol>
 *
 * <p>被拒绝时**不推送动作事件**，只把原因回给模型。这一点很要紧：如果越权提议也弹出确认卡片，
 * 只是点了才报错，那等于把「平台里存在这台车」这个事实透露给了看不到它的人。
 */
@Service
public class ActionProposalService {

    private final VehicleService vehicles;
    private final AlarmRepository alarms;

    public ActionProposalService(VehicleService vehicles, AlarmRepository alarms) {
        this.vehicles = vehicles;
        this.alarms = alarms;
    }

    /** 当前发起人能用的动作。用于裁剪交给模型的工具契约——它看不到的就不会提议。 */
    public List<ActionType> availableTo(AuthorizedPrincipal principal) {
        List<ActionType> available = new ArrayList<>();
        for (ActionType type : ActionType.values()) {
            if (principal.hasPermission(type.requiredPermission())) {
                available.add(type);
            }
        }
        return available;
    }

    /**
     * 校验一条提议。
     *
     * @return 通过时是提议本身，失败时是给模型看的原因
     */
    public Outcome propose(
            ToolSession session,
            String typeName,
            String title,
            String reason,
            Map<String, Object> params,
            ConfirmationPolicy policy) {

        Optional<ActionType> resolved = ActionType.of(typeName);
        if (resolved.isEmpty()) {
            return Outcome.rejected("不支持的动作类型：" + typeName + "。你只能提议平台明确开放的动作。");
        }
        ActionType type = resolved.get();

        if (!session.principal().hasPermission(type.requiredPermission())) {
            // 不透露该动作存在于平台的哪一层，只说当前用户做不了。
            return Outcome.rejected("当前用户没有执行「" + type.label() + "」的权限，请改为向用户说明。");
        }

        Map<String, Object> checked = new LinkedHashMap<>(params == null ? Map.of() : params);
        String fieldProblem = validateFields(type, checked, session.principal().platform());
        if (fieldProblem != null) {
            return Outcome.rejected(fieldProblem);
        }
        String problem = validateTarget(type, checked, session);
        if (problem != null) {
            return Outcome.rejected(problem);
        }

        String finalTitle = title == null || title.isBlank() ? type.label() : title.trim();
        return Outcome.accepted(new ActionProposal(
                "p_" + UUID.randomUUID(), type, finalTitle,
                reason == null ? "" : reason.trim(), checked,
                policy.requiresConfirmation(type)));
    }

    /**
     * 字段名必须与既有接口一致，必填项不能缺。
     *
     * <p>拒绝未知字段而不是默默丢弃：模型把 plateNo 写成 plate 时，静默丢弃会生成一张缺了车牌的
     * 确认卡片，用户点了才失败；明确告诉它字段名错了，它这一轮就能改对。
     */
    private String validateFields(ActionType type, Map<String, Object> params, boolean platform) {
        List<String> missing = new ArrayList<>();
        // 平台管理员不属于任何租户，新建租户内的对象时必须显式指定归属，后端也是这么要求的。
        // 放到这里拦，而不是等用户点了确认再由后端抛「请先选择车辆所属租户」——那时候已经晚了。
        if (platform && type.optionalFields().contains("tenantId")
                && !(params.get("tenantId") instanceof Number)) {
            missing.add("tenantId（平台管理员必填；先用 list_tenants 查到数字 id）");
        }
        for (String field : type.requiredFields()) {
            Object value = params.get(field);
            if (value == null || (value instanceof String text && text.isBlank())) {
                missing.add(field);
            }
        }
        List<String> unknown = new ArrayList<>();
        List<String> badType = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!type.knows(entry.getKey())) {
                unknown.add(entry.getKey());
            } else if (!typeOk(entry.getKey(), entry.getValue())) {
                badType.add(entry.getKey() + ActionType.fieldHint(entry.getKey()));
            }
        }
        if (missing.isEmpty() && unknown.isEmpty() && badType.isEmpty()) {
            return null;
        }
        StringBuilder problem = new StringBuilder("参数不符合「").append(type.label()).append("」的要求：");
        if (!missing.isEmpty()) {
            problem.append("缺少必填字段 ").append(String.join("、", missing)).append("；");
        }
        if (!unknown.isEmpty()) {
            problem.append("不认识的字段 ").append(String.join("、", unknown)).append("；");
        }
        if (!badType.isEmpty()) {
            problem.append("类型不对的字段 ").append(String.join("、", badType)).append("；");
        }
        problem.append("请严格使用这些字段名重新提议——必填：")
                .append(String.join("、", type.requiredFields()));
        if (!type.optionalFields().isEmpty()) {
            problem.append("；可选：").append(String.join("、", type.optionalFields()));
        }
        return problem.toString();
    }

    /**
     * 值的类型是否对得上。
     *
     * <p>只给字段名不校验类型，模型会把 tenantId 填成用户名「admin」——名字是对的，值是错的，
     * 一路放行到用户点确认才炸出一句「服务器内部错误」。在这里拦下并回告，它当轮就能改对。
     */
    private static boolean typeOk(String field, Object value) {
        if (value == null) {
            return true;
        }
        return switch (field) {
            case "tenantId", "departmentId", "planId", "id", "alarmId", "channelCount",
                 "maxVehicles", "maxAccounts", "maxAiCallsMonthly", "priceCents", "periodMonths" ->
                    value instanceof Number;
            case "centerGcjLat", "centerGcjLng", "radiusMeters", "speedLimitKph" ->
                    value instanceof Number;
            case "enabled", "alertOnEnter", "alertOnExit" -> value instanceof Boolean;
            case "deviceIds", "vehicleIds" -> value instanceof List<?>;
            default -> true;
        };
    }

    /**
     * 目标资源必须在发起人的数据范围内可见。
     *
     * <p>只校验「引用了已有对象」的动作；纯创建类没有目标可查，其字段合法性由既有接口在执行时
     * 判定——刻意不在这里抢着校验一遍，两处规则迟早会分叉，而接口那一份才是权威。
     */
    private String validateTarget(ActionType type, Map<String, Object> params, ToolSession session) {
        return switch (type) {
            case ALARM_ACKNOWLEDGE, ALARM_CLOSE -> requireVisibleAlarm(params, session);
            case VEHICLE_UPDATE, VEHICLE_DELETE, SEND_TEXT -> requireVisibleVehicle(params, session);
            default -> null;
        };
    }

    private String requireVisibleAlarm(Map<String, Object> params, ToolSession session) {
        Object raw = params.get("alarmId");
        if (!(raw instanceof Number id)) {
            return "缺少 alarmId，或它不是数字。请先用告警查询工具拿到具体的告警编号。";
        }
        if (alarms.findById(id.longValue(), session.scope()).isEmpty()) {
            // 与「不存在」用同一句话：区分二者会泄露该告警存在于别的租户。
            return "未找到编号为 " + id.longValue() + " 的告警。";
        }
        return null;
    }

    private String requireVisibleVehicle(Map<String, Object> params, ToolSession session) {
        Object raw = params.get("deviceId");
        if (!(raw instanceof String deviceId) || deviceId.isBlank()) {
            return "缺少 deviceId。请先用车辆查询工具确认具体是哪一台车。";
        }
        try {
            vehicles.get(deviceId.trim(), session.scope());
            return null;
        } catch (RuntimeException notVisible) {
            return "未找到设备号为 " + deviceId.trim() + " 的车辆。";
        }
    }

    /** 校验结果。{@code proposal} 为空时 {@code message} 是给模型看的拒绝原因。 */
    public record Outcome(ActionProposal proposal, String message) {

        static Outcome accepted(ActionProposal proposal) {
            return new Outcome(proposal, null);
        }

        static Outcome rejected(String message) {
            return new Outcome(null, message);
        }

        public boolean accepted() {
            return proposal != null;
        }
    }
}
