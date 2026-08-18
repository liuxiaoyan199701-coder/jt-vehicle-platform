package io.github.jtconsole.ai.briefing;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一条候选发现：由**普通代码**算出来的事实。
 *
 * <p><b>这是整套简报的信任基础</b>。模型拿到的是这样一份清单，它的任务是从中挑几条、把同类归并、
 * 用运营的话说出来——**不是去发现事实，也不许引入新数字**。每条要点必须引用一个候选的
 * {@link #id()}，引用不到的整条丢弃（见 {@code BriefingNormalizer}）。
 *
 * <p>这么分工的理由很直接：「哪台车离线超过 6 小时」是查询，代码做得又快又准；
 * 「这二十条里今天哪三条值得说」是判断，代码做不了。反过来让模型去查、去算，
 * 等于把一份准确数据经过一次幻觉信道再拿回来。
 *
 * @param id          稳定标识，形如 {@code offline-1}。模型必须引用它
 * @param category    类别，用于前端选图标与分组
 * @param severity    严重度。由代码按阈值判定，**模型不能改**
 * @param summary     一句话事实陈述，给模型当素材（模型可以改写措辞，但不能改数字）
 * @param facts       支撑数据，原样透传到前端展示，不经模型
 * @param deviceIds   本条涉及的设备号。**读取时按数据范围过滤要用它**，聚合类发现为空列表
 * @param link        可选的导航目标（前端路由名与查询参数），没有则为 null
 */
public record DashboardFinding(
        String id,
        Category category,
        Severity severity,
        String summary,
        Map<String, Object> facts,
        List<String> deviceIds,
        Link link) {

    public DashboardFinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        deviceIds = deviceIds == null ? List.of() : List.copyOf(deviceIds);
    }

    /** 是否是租户级聚合结论。这类发现无法按数据范围部分过滤，范围不全时整条不给。 */
    public boolean aggregate() {
        return deviceIds.isEmpty();
    }

    public enum Category {
        /** 车辆长时间离线 */
        OFFLINE,
        /** 告警量异常 */
        ALARM,
        /** 里程或活跃度异常 */
        MILEAGE,
        /** 摄像头画面异常（来自视觉巡检） */
        CAMERA,
        /** 车队总体状况 */
        FLEET
    }

    /**
     * 严重度。
     *
     * <p>由代码按阈值判定而不是让模型评估：同一件事今天说「严重」明天说「一般」，
     * 会让人无法据此排优先级，而排优先级正是这块看板存在的意义。
     */
    public enum Severity {
        INFO,
        WARN,
        CRITICAL
    }

    /**
     * 导航目标。
     *
     * <p><b>刻意只给路由名与参数，不给可执行动作。</b>简报是缓存的、跨用户共享的，
     * 而动作卡要按发起人权限逐次校验。把一张「下发文本」的卡片缓存下来发给所有人，
     * 权限模型就破了。要执行动作，在问答框里说一句——那条路径每次都会重新校验。
     */
    public record Link(String routeName, Map<String, String> query, String label) {
        public Link {
            Objects.requireNonNull(routeName, "routeName");
            query = query == null ? Map.of() : Map.copyOf(query);
        }
    }
}
