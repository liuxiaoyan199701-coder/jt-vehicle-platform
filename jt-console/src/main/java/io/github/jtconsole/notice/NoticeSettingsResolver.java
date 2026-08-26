package io.github.jtconsole.notice;

import io.github.jtconsole.ai.briefing.DashboardFinding.Severity;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.iam.ConfigKeys;
import io.github.jtconsole.iam.TenantConfigService;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把「全局默认 + 租户覆盖」解析成一份可用的通知策略。
 *
 * <p>走既有的配置中心（{@code tenant_config} 两级解析 + 30 秒缓存），不新造一套存储：
 * 「值不值得打扰」在不同车队相差很大，而这正是配置中心已经在做的事。
 *
 * <p><b>取值不合法时回落到默认而不是抛异常</b>：一个手滑写成 {@code WARNING} 的配置项
 * 不该让整轮通知生成挂掉——那会同时损失所有租户的通知，而错的只有一个租户的一行配置。
 */
@Service
public class NoticeSettingsResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoticeSettingsResolver.class);

    /** 窗口下限。允许配 0 等于取消抑制，那是「每小时都通知」，几乎一定是误配。 */
    private static final Duration MIN_WINDOW = Duration.ofMinutes(1);

    private final ConsoleProperties properties;
    private final TenantConfigService configs;

    public NoticeSettingsResolver(ConsoleProperties properties, TenantConfigService configs) {
        this.properties = properties;
        this.configs = configs;
    }

    /** 主动通知的总闸。租户不可覆盖。 */
    public boolean enabled() {
        return properties.getNotice().isEnabled();
    }

    public NoticeSettings forTenant(long tenantId) {
        ConsoleProperties.Notice defaults = properties.getNotice();
        return new NoticeSettings(
                severity(tenantId, ConfigKeys.NOTICE_MIN_SEVERITY, defaults.getMinSeverity()),
                hours(tenantId, ConfigKeys.NOTICE_SILENCE_CRITICAL_HOURS,
                        defaults.getSilenceWindow().getCritical()),
                hours(tenantId, ConfigKeys.NOTICE_SILENCE_WARN_HOURS,
                        defaults.getSilenceWindow().getWarn()));
    }

    private Severity severity(long tenantId, String key, String fallback) {
        return value(tenantId, key)
                .flatMap(NoticeSettingsResolver::parseSeverity)
                .or(() -> parseSeverity(fallback))
                .orElse(Severity.WARN);
    }

    private Duration hours(long tenantId, String key, Duration fallback) {
        Optional<String> configured = value(tenantId, key);
        if (configured.isEmpty()) {
            return atLeastMinimum(fallback);
        }
        try {
            return atLeastMinimum(Duration.ofMinutes(
                    Math.round(Double.parseDouble(configured.get().trim()) * 60)));
        } catch (RuntimeException notANumber) {
            LOGGER.warn("租户 {} 的通知配置 {} 不是数字，按默认值处理", tenantId, key);
            return atLeastMinimum(fallback);
        }
    }

    private Optional<String> value(long tenantId, String key) {
        return configs.value(tenantId, key);
    }

    private static Duration atLeastMinimum(Duration window) {
        return window == null || window.compareTo(MIN_WINDOW) < 0 ? MIN_WINDOW : window;
    }

    private static Optional<Severity> parseSeverity(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Severity.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
