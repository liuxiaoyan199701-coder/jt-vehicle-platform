package io.github.jtconsole.iam;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.repository.TenantConfigRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配置中心：租户覆盖 → 全局默认 两级解析。
 *
 * <p>全局默认存在 {@code tenant_id = 0} 这一保留作用域，未配置时回落到 application.yml。
 * 解析结果带 30 秒缓存，与权限解析同一量级——配置读取在每次进入地图页时都会发生。
 */
@Service
public class TenantConfigService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final String MASKED = "********";

    private final TenantConfigRepository configs;
    private final ConsoleProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public TenantConfigService(TenantConfigRepository configs, ConsoleProperties properties) {
        this(configs, properties, Clock.systemUTC());
    }

    TenantConfigService(
            TenantConfigRepository configs, ConsoleProperties properties, Clock clock) {
        this.configs = configs;
        this.properties = properties;
        this.clock = clock;
    }

    /** 当前会话租户的生效配置。敏感值原样返回——前端要拿它去初始化地图 SDK。 */
    @Transactional(readOnly = true)
    public Map<String, String> effectiveFor(AuthorizedPrincipal caller) {
        long scopeId = caller.tenantId() == null
                ? Tenant.GLOBAL_CONFIG_SCOPE
                : caller.tenantId();
        return resolve(scopeId);
    }

    /** 管理界面用的取值列表，敏感项脱敏。 */
    @Transactional(readOnly = true)
    public Map<String, String> overridesFor(AuthorizedPrincipal caller, Long tenantFilter) {
        long scopeId = resolveWritableScope(caller, tenantFilter);
        Map<String, String> stored = configs.findByScope(scopeId);
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            masked.put(entry.getKey(),
                    ConfigKeys.sensitive(entry.getKey()) && notBlank(entry.getValue())
                            ? MASKED
                            : entry.getValue());
        }
        return masked;
    }

    @Transactional
    public void save(AuthorizedPrincipal caller, Long tenantFilter, Map<String, String> values) {
        long scopeId = resolveWritableScope(caller, tenantFilter);
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!ConfigKeys.exists(entry.getKey())) {
                throw IamException.invalid("配置键不存在：" + entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            // 脱敏占位符表示「未修改」，不能把 ******** 写回去。
            if (MASKED.equals(value)) {
                continue;
            }
            if (value == null || value.isBlank()) {
                configs.delete(scopeId, entry.getKey());
            } else {
                configs.upsert(scopeId, entry.getKey(), value.trim());
            }
        }
        cache.remove(scopeId);
    }

    /** 单个配置的生效值，供服务端逻辑使用。 */
    public Optional<String> value(Long tenantId, String key) {
        long scopeId = tenantId == null ? Tenant.GLOBAL_CONFIG_SCOPE : tenantId;
        return Optional.ofNullable(resolve(scopeId).get(key)).filter(TenantConfigService::notBlank);
    }

    public void invalidate(long tenantId) {
        cache.remove(tenantId);
    }

    private Map<String, String> resolve(long scopeId) {
        Instant now = clock.instant();
        CacheEntry cached = cache.get(scopeId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.values();
        }
        Map<String, String> resolved = new LinkedHashMap<>(globalDefaults());
        if (scopeId != Tenant.GLOBAL_CONFIG_SCOPE) {
            configs.findByScope(Tenant.GLOBAL_CONFIG_SCOPE).forEach((key, value) -> {
                if (notBlank(value)) {
                    resolved.put(key, value);
                }
            });
        }
        configs.findByScope(scopeId).forEach((key, value) -> {
            if (notBlank(value)) {
                resolved.put(key, value);
            }
        });
        Map<String, String> immutable = Map.copyOf(resolved);
        cache.put(scopeId, new CacheEntry(immutable, now.plus(CACHE_TTL)));
        return immutable;
    }

    private Map<String, String> globalDefaults() {
        ConsoleProperties.Tenancy tenancy = properties.getTenancy();
        ConsoleProperties.Notice notice = properties.getNotice();
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(ConfigKeys.PLATFORM_NAME, tenancy.getPlatformName());
        defaults.put(ConfigKeys.AMAP_KEY, tenancy.getAmapKey());
        defaults.put(ConfigKeys.AMAP_SECURITY_CODE, tenancy.getAmapSecurityCode());
        defaults.put(ConfigKeys.MOVING_SPEED_THRESHOLD, "5");
        defaults.put(ConfigKeys.NOTICE_MIN_SEVERITY, notice.getMinSeverity());
        defaults.put(ConfigKeys.NOTICE_SILENCE_CRITICAL_HOURS,
                String.valueOf(notice.getSilenceWindow().getCritical().toHours()));
        defaults.put(ConfigKeys.NOTICE_SILENCE_WARN_HOURS,
                String.valueOf(notice.getSilenceWindow().getWarn().toHours()));
        return defaults;
    }

    /**
     * 可写作用域：租户用户只能改本租户；平台管理员指定租户即改该租户，
     * 不指定即改全局默认。
     */
    private long resolveWritableScope(AuthorizedPrincipal caller, Long tenantFilter) {
        if (!caller.platform()) {
            if (caller.tenantId() == null) {
                throw IamException.invalid("当前账号没有可配置的租户");
            }
            return caller.tenantId();
        }
        return tenantFilter == null ? Tenant.GLOBAL_CONFIG_SCOPE : tenantFilter;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record CacheEntry(Map<String, String> values, Instant expiresAt) {}
}
