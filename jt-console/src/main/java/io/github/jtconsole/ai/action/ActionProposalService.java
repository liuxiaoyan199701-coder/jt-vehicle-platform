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
