package io.github.jtconsole.ai.view;

import io.github.jtconsole.ai.tool.ToolSession;
import io.github.jtconsole.operations.VehicleService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 校验并构造视图提议。
 *
 * <p>四道闸门，顺序有意义：
 * <ol>
 *   <li><b>类型在白名单内</b>。</li>
 *   <li><b>发起人持有查看该类内容的权限</b>——复用既有权限码。</li>
 *   <li><b>参数可用</b>：字段存在、类型对、取值在范围内、代价有上限。</li>
 *   <li><b>目标资源在数据范围内可见</b>。</li>
 * </ol>
 *
 * <p><b>第三道在只读视图上反而更重，不能省。</b>动作卡片至少还有「用户点确认」那一关，用户会看
 * 一眼参数；视图是自动就去取数的，中间没有人看。参数越界的后果很具体：通道号越界会让前端去开流，
 * 于是网关向路上那台车下发一条无效指令、终端不响应、用户对着「唤醒中」转半分钟，还留下一条审计；
 * 时间跨度不限会让前端去查一个几年的范围，触发上万点的全表扫描——AI 就成了自助的拒绝服务触发器。
 *
 * <p>被拒绝时**不推送视图事件**。理由与动作提议那边不完全一样，值得写清楚：不是防「存在性泄露」
 * （模型多半已经用查询工具确认过这台车了），而是**防止界面上出现一块点了必然失败的内容**——
 * 用户会反复点，然后认为平台坏了。拒绝话术仍与动作那边保持一致（「未找到设备号为 X 的车辆」），
 * 因为模型没查过就凭空提议的情况确实存在。
 */
@Service
public class ViewProposalService {

    private final VehicleService vehicles;

    public ViewProposalService(VehicleService vehicles) {
        this.vehicles = vehicles;
    }

    /**
     * 校验一条引用型视图提议。
     *
     * @return 通过时是提议本身，失败时是给模型看的原因
     */
    public Outcome propose(
            ToolSession session, String typeName, String title, Map<String, Object> params) {

        Optional<ViewType> resolved = ViewType.of(typeName);
        if (resolved.isEmpty()) {
            return Outcome.rejected("不支持的视图类型：" + typeName + "。你只能展示平台明确开放的视图。");
        }
        ViewType type = resolved.get();

        if (!session.principal().hasPermission(type.requiredPermission())) {
            return Outcome.rejected(
                    "当前用户没有查看「" + type.label() + "」的权限，请改为用文字说明。");
        }

        Map<String, Object> checked = new LinkedHashMap<>(params == null ? Map.of() : params);
        checked.values().removeIf(value -> value == null
                || (value instanceof String text && text.isBlank()));

        String problem = validateFields(type, checked);
        if (problem == null) {
            problem = validateTarget(type, checked, session);
        }
        if (problem != null) {
            return Outcome.rejected(problem);
        }

        // 配额放在最后扣：被拒绝的提议不该消耗名额，否则模型改对参数重试几次就把额度耗光了。
        if (!session.viewBudget().tryConsume(signature(type, checked))) {
            return Outcome.rejected("这个视图本轮已经展示过了，或已达每轮 "
                    + session.viewBudget().limit() + " 个的上限。不要重复展示同一台车的同类视图。");
        }

        String finalTitle = title == null || title.isBlank() ? type.label() : title.trim();
        return Outcome.accepted(new ViewProposal(
                "v_" + UUID.randomUUID(), type, finalTitle, checked));
    }

    /**
     * 校验一条图表提议（快照型）。
     *
     * <p>与引用型的区别是**没有可解引用的目标**：数值由模型带来，没有资源需要校验可见性。
     * 剩下的只有结构自洽与体积——这正是按载荷来源分轨的自然结果，不是特例。
     */
    public Outcome propose(ToolSession session, ChartSpec raw) {
        ViewType type = ViewType.CHART;
        if (!session.principal().hasPermission(type.requiredPermission())) {
            return Outcome.rejected("当前用户没有使用助手的权限。");
        }
        ChartSpecNormalizer.Result normalized = ChartSpecNormalizer.normalize(raw);
        if (!normalized.ok()) {
            return Outcome.rejected(normalized.problem());
        }
        ChartSpec spec = normalized.spec();
        // 图表没有 deviceId 这种主对象，签名用标题加分类——同一张图重复提议才算重复。
        if (!session.viewBudget().tryConsume("chart:" + spec.title() + spec.categories())) {
            return Outcome.rejected("这张图本轮已经展示过了，或已达每轮 "
                    + session.viewBudget().limit() + " 个视图的上限。");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chartType", spec.chartType());
        params.put("source", spec.source());
        params.put("categories", spec.categories());
        params.put("series", spec.series().stream().map(series -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", series.name());
            row.put("type", series.type());
            row.put("unit", series.unit());
            row.put("data", series.data());
            return row;
        }).toList());
        params.put("stacked", spec.stacked());
        String title = spec.title() == null || spec.title().isBlank() ? type.label() : spec.title();
        return Outcome.accepted(new ViewProposal("v_" + UUID.randomUUID(), type, title, params));
    }

    /**
     * 视图的去重签名：**类型 + 主对象**，不含时间窗。
     *
     * <p>不含时间窗是关键：模型探索时会把窗口层层收窄反复出图（线上实测一次问话推了四张同一段
     * 行程的嵌套地图），而对用户来说那就是同一张。换成另一台车则签名不同，照常放行。
     */
    private static String signature(ViewType type, Map<String, Object> params) {
        Object target = params.get("deviceId");
        return type.wireName() + ':' + (target == null ? params.toString() : target);
    }

    /**
     * 字段名必须在白名单声明的范围内，必填项不能缺。
     *
     * <p>拒绝未知字段而不是默默丢弃：模型把 deviceId 写成 device 时，静默丢弃会渲染出一张
     * 「全部车辆」的地图，用户以为看的是那一台——错得没有任何提示。
     */
    private String validateFields(ViewType type, Map<String, Object> params) {
        for (String field : type.requiredFields()) {
            if (!params.containsKey(field)) {
                return "展示「" + type.label() + "」缺少必填参数 " + field
                        + ViewType.fieldHint(field) + "。";
            }
        }
        for (String field : params.keySet()) {
            if (!type.knows(field)) {
                return "「" + type.label() + "」不接受参数 " + field + "。可用参数："
                        + String.join("、", type.requiredFields())
                        + (type.optionalFields().isEmpty()
                                ? "" : "（可选：" + String.join("、", type.optionalFields()) + "）");
            }
        }
        return null;
    }

    /**
     * 目标资源必须在发起人的数据范围内可见。
     *
     * <p>实时位置的 deviceId 是可选的——留空表示「全部在线车辆」，那本来就受数据范围约束，
     * 没有单独的目标可校验。
     */
    private String validateTarget(ViewType type, Map<String, Object> params, ToolSession session) {
        return switch (type) {
            case LIVE_MAP -> params.containsKey("deviceId")
                    ? requireVisibleVehicle(params, session) : null;
            case TRACK_MAP -> {
                String problem = requireVisibleVehicle(params, session);
                yield problem != null ? problem : requireSaneSpan(params);
            }
            case LIVE_VIDEO -> {
                String problem = requireVisibleVehicle(params, session);
                yield problem != null ? problem : requireExistingChannel(params, session);
            }
            // 快照型不走这条路径，它有自己的入口。
            case CHART -> null;
        };
    }

    private String requireVisibleVehicle(Map<String, Object> params, ToolSession session) {
        Object raw = params.get("deviceId");
        if (!(raw instanceof String deviceId) || deviceId.isBlank()) {
            return "deviceId 必须是设备号字符串。请先用车辆查询工具确认具体是哪一台车。";
        }
        try {
            vehicles.get(deviceId.trim(), session.scope());
            return null;
        } catch (RuntimeException notVisible) {
            // 与「不存在」同一句话，与动作提议那边保持一致。
            return "未找到设备号为 " + deviceId.trim() + " 的车辆。";
        }
    }

    /**
     * 时间跨度必须有上限。
     *
     * <p>不限的后果很具体：前端会去查一个几年的范围，触发上万点的全表扫描——**AI 就成了自助的
     * 拒绝服务触发器**，而这中间没有任何人看过参数。
     */
    private String requireSaneSpan(Map<String, Object> params) {
        LocalDateTime from = parseLocal(params.get("start"));
        LocalDateTime to = parseLocal(params.get("end"));
        if (from == null || to == null) {
            return "start 与 end 必须是 yyyy-MM-ddTHH:mm:ss 格式的时间。";
        }
        if (!to.isAfter(from)) {
            return "end 必须晚于 start。";
        }
        if (Duration.between(from, to).toHours() > ViewType.MAX_TRACK_HOURS) {
            return "轨迹时间跨度不能超过 " + ViewType.MAX_TRACK_HOURS
                    + " 小时。请缩小范围，或分多次查看。";
        }
        return null;
    }

    private static LocalDateTime parseLocal(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim().replace(' ', 'T'));
        } catch (DateTimeParseException notATime) {
            return null;
        }
    }

    /**
     * 通道号不能超过该车实际的通道数。
     *
     * <p>不拦的后果：前端会去开流，于是**网关向路上那台车下发一条 9101 指令**，终端不响应，
     * 用户对着「唤醒设备中」转半分钟，最后还留下一条审计记录。而通道数服务端本来就知道。
     */
    private String requireExistingChannel(Map<String, Object> params, ToolSession session) {
        Object raw = params.get("channel");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            return "channel 必须是数字。";
        }
        int channel = number.intValue();
        if (channel < 1) {
            return "channel 从 1 开始。";
        }
        String deviceId = String.valueOf(params.get("deviceId")).trim();
        try {
            int count = vehicles.get(deviceId, session.scope()).channelCount();
            if (count > 0 && channel > count) {
                return "这台车只有 " + count + " 路摄像头，没有第 " + channel + " 路。";
            }
        } catch (RuntimeException notVisible) {
            return "未找到设备号为 " + deviceId + " 的车辆。";
        }
        return null;
    }

    /** 校验结果。{@code proposal} 为空时 {@code message} 是给模型看的拒绝原因。 */
    public record Outcome(ViewProposal proposal, String message) {

        static Outcome accepted(ViewProposal proposal) {
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
