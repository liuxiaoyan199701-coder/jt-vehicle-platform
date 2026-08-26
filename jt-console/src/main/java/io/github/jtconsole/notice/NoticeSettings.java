package io.github.jtconsole.notice;

import io.github.jtconsole.ai.briefing.DashboardFinding.Severity;
import java.time.Duration;

/**
 * 一个租户此刻生效的通知策略。
 *
 * @param minSeverity     低于它的发现不产生通知
 * @param criticalWindow  {@code CRITICAL} 的静默窗口
 * @param warnWindow      {@code WARN} 及以下的静默窗口
 */
public record NoticeSettings(
        Severity minSeverity, Duration criticalWindow, Duration warnWindow) {

    /**
     * 该严重度的静默窗口。
     *
     * <p>{@code INFO} 跟着 {@code WARN} 走而不是另设一档：它默认压根不通知，
     * 为一个默认关闭的档位再加一个旋钮，只会让配置界面多一行没人懂的东西。
     */
    public Duration windowFor(Severity severity) {
        return severity == Severity.CRITICAL ? criticalWindow : warnWindow;
    }

    /** 够不够格打扰人。 */
    public boolean worthNotifying(Severity severity) {
        return severity.ordinal() >= minSeverity.ordinal();
    }
}
