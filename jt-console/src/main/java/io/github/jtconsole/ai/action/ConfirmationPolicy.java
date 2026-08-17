package io.github.jtconsole.ai.action;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 判定某个动作要不要用户点确认。
 *
 * <p>默认全部需要确认。租户可以把可逆的动作配成免确认，但有一条**不接受配置覆盖**的底线：
 * {@link ActionType#alwaysConfirm()} 为 true 的动作永远要确认。
 *
 * <p>底线之所以硬编码在这里而不是做成「默认值」，是因为真正要防的场景恰恰是「有人为了图省事
 * 把开关全打开」。一个能被配置关掉全部安全检查的设计，等于没有安全设计。
 */
public final class ConfirmationPolicy {

    private static final ConfirmationPolicy CONFIRM_EVERYTHING = new ConfirmationPolicy(Set.of());

    /** 被允许免确认的动作。已经过滤掉底线动作，因此这里出现的都是真正可以免确认的。 */
    private final Set<ActionType> autoExecutable;

    private ConfirmationPolicy(Set<ActionType> autoExecutable) {
        this.autoExecutable = Set.copyOf(autoExecutable);
    }

    /** 默认策略：每一个动作都要确认。 */
    public static ConfirmationPolicy confirmEverything() {
        return CONFIRM_EVERYTHING;
    }

    /**
     * 从配置构造。
     *
     * <p>配置里若写了底线动作，**静默忽略**而不是报错：配置往往是运维在界面上勾出来的，
     * 为一个勾不动的选项让整个配置加载失败得不偿失。真正的保证在于它进不了 {@link #autoExecutable}。
     *
     * @param configured 配置为免确认的动作名，未知名称一并忽略
     */
    public static ConfirmationPolicy autoExecuting(Set<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return CONFIRM_EVERYTHING;
        }
        Set<ActionType> allowed = new LinkedHashSet<>();
        for (String name : configured) {
            ActionType.of(name)
                    .filter(type -> !type.alwaysConfirm())
                    .ifPresent(allowed::add);
        }
        return allowed.isEmpty() ? CONFIRM_EVERYTHING : new ConfirmationPolicy(allowed);
    }

    /** 便捷构造：直接给定动作类型。 */
    public static ConfirmationPolicy autoExecutingTypes(Set<ActionType> types) {
        Set<ActionType> allowed = new LinkedHashSet<>();
        for (ActionType type : types) {
            if (!type.alwaysConfirm()) {
                allowed.add(type);
            }
        }
        return allowed.isEmpty() ? CONFIRM_EVERYTHING : new ConfirmationPolicy(allowed);
    }

    public boolean requiresConfirmation(ActionType type) {
        return type.alwaysConfirm() || !autoExecutable.contains(type);
    }

    /** 便于诊断与界面展示：当前实际生效的免确认动作。 */
    public Set<String> autoExecutableNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ActionType type : autoExecutable) {
            names.add(type.wireName());
        }
        return names;
    }

    /** 配置里那些被底线拒绝掉的项，用于向运维解释「为什么勾了还是要确认」。 */
    public static Set<String> rejectedByFloor(Set<String> configured) {
        Set<String> rejected = new LinkedHashSet<>();
        if (configured == null) {
            return rejected;
        }
        for (String name : configured) {
            ActionType.of(name)
                    .filter(ActionType::alwaysConfirm)
                    .ifPresent(type -> rejected.add(type.wireName()));
        }
        return rejected;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
