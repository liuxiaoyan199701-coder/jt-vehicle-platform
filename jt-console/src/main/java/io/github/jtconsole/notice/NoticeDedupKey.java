package io.github.jtconsole.notice;

import io.github.jtconsole.ai.briefing.DashboardFinding;
import java.util.ArrayList;
import java.util.List;

/**
 * 去重键：**认现实中的那件事，不认它在清单里排第几**。
 *
 * <p>这是整套主动通知里最容易做错的一处，而且做错了**不会报任何错**——只会反复通知，
 * 然后用户永久关掉这个功能。
 *
 * <p>不能用 {@link DashboardFinding#id()} 当键，因为发现的 id 稳定性是**混着的**：
 * 离线发现的 id 是 {@code "offline-" + 序号}，序号来自一次按静默时长倒序的排序，
 * 同一台车今天是 {@code offline-1}，明天来了一台离线更久的就变成 {@code offline-2}；
 * 而 {@code alarm-surge}、{@code connection-registration-<设备号>} 这类又是稳定的。
 * 拿 id 当键，会在离线这一类上静默地反复通知——而离线恰恰是最常见的一类。
 *
 * <pre>
 * 设备类发现（deviceIds 非空）  → 键 = 类别 + ":" + 设备号
 * 聚合类发现（deviceIds 为空）  → 键 = 类别 + ":" + 发现 id
 * </pre>
 *
 * <p>多设备发现按每个设备号各出一条键：「这台车怎么了」才是人关心的粒度，
 * 合并成一条会让几台车的抑制窗口互相牵连——甲车触发过之后，乙车出问题就被压掉了。
 */
public final class NoticeDedupKey {

    private NoticeDedupKey() {
    }

    /**
     * @param deviceId 该条通知针对的设备号；聚合类发现传 {@code null}
     */
    public static String of(DashboardFinding finding, String deviceId) {
        String entity = deviceId == null || deviceId.isBlank() ? finding.id() : deviceId;
        return finding.category().name() + ":" + entity;
    }

    /**
     * 一条发现要产出几条通知、各自认哪个键。
     *
     * <p>聚合类一条，设备类每个设备各一条。
     */
    public static List<Target> targetsOf(DashboardFinding finding) {
        if (finding.aggregate()) {
            return List.of(new Target(null, of(finding, null)));
        }
        List<Target> targets = new ArrayList<>(finding.deviceIds().size());
        for (String deviceId : finding.deviceIds()) {
            targets.add(new Target(deviceId, of(finding, deviceId)));
        }
        return List.copyOf(targets);
    }

    /**
     * 一条待产出的通知认哪个设备、哪个键。
     *
     * @param deviceId 聚合类为 {@code null}
     */
    public record Target(String deviceId, String key) {}
}
