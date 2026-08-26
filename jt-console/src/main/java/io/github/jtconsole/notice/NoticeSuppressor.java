package io.github.jtconsole.notice;

import io.github.jtconsole.ai.briefing.DashboardFinding;
import io.github.jtconsole.ai.briefing.DashboardFinding.Severity;
import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Notice;
import io.github.jtconsole.repository.NoticeRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「这件事值不值得再打扰一次」的判定。
 *
 * <p><b>抑制是主动通知的主体而不是附属</b>：唯一会让这个功能彻底失败的方式，是它太吵，
 * 然后用户永久关掉它。
 *
 * <pre>
 * 低于最低级别                  → 不通知
 * 该键在静默窗口内已通知过：
 *     本次严重度高于上次        → 通知（升级豁免），窗口从这次重新算
 *     否则                      → 跳过
 * 否则                          → 通知
 * </pre>
 *
 * <p>状态查库而不是记在内存里：简报调度会因重启而重跑，内存窗口一重启就全忘了，
 * 那意味着每次发版都把所有还在持续的问题重新通知一遍。
 */
@Component
public class NoticeSuppressor {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoticeSuppressor.class);

    private final NoticeRepository notices;
    private final NoticeSettingsResolver settings;

    public NoticeSuppressor(NoticeRepository notices, NoticeSettingsResolver settings) {
        this.notices = notices;
        this.settings = settings;
    }

    /**
     * @param deviceId 该条通知针对的设备号；聚合类发现传 {@code null}
     */
    public boolean shouldNotify(long tenantId, DashboardFinding finding, String deviceId) {
        NoticeSettings resolved = settings.forTenant(tenantId);
        if (!resolved.worthNotifying(finding.severity())) {
            return false;
        }
        String key = NoticeDedupKey.of(finding, deviceId);
        Optional<Notice> previous = notices.findLatestByDedupKey(tenantId, key);
        if (previous.isEmpty()) {
            return true;
        }
        Notice last = previous.get();
        if (windowHasPassed(last, resolved.windowFor(finding.severity()))) {
            return true;
        }
        // 升级豁免：离线在 24 小时处由 WARN 翻成 CRITICAL 是真实的恶化，
        // 压掉它等于把最该说的那一次说没了。反向（CRITICAL 回落 WARN）不通知。
        return escalated(finding.severity(), last.severity());
    }

    private static boolean windowHasPassed(Notice last, Duration window) {
        Optional<Instant> notifiedAt = Timestamps.toLocalDateTime(last.createdAt())
                .map(local -> local.atOffset(Timestamps.ZONE).toInstant());
        if (notifiedAt.isEmpty()) {
            // 我们自己写进去的时间戳一律走 Timestamps，走到这里说明库里的值被外部改过。
            // 宁可多通知一次并留下这行日志，也不要因为一个认不出的时间戳永久静默——
            // 「该说的没说」正是本功能存在的理由。
            LOGGER.warn("通知 {} 的产生时间无法解析：{}，按窗口已过处理",
                    last.id(), last.createdAt());
            return true;
        }
        return !Instant.now().isBefore(notifiedAt.get().plus(window));
    }

    /**
     * 上次的严重度认不出时**不算升级**：那会让每一轮都被判成「恶化了」而反复通知，
     * 正是抑制要防的那种失效。
     */
    private static boolean escalated(Severity current, String previous) {
        try {
            return current.ordinal() > Severity.valueOf(previous).ordinal();
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return false;
        }
    }
}
