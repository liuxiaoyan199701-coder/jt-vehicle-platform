package io.github.jtconsole.operations;

import io.github.jtconsole.domain.AlarmDefinition;
import io.github.jtconsole.domain.AlarmRule;
import io.github.jtconsole.domain.AlarmRuleCandidate;
import io.github.jtconsole.domain.AlarmRuleType;
import io.github.jtconsole.domain.AlarmSource;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.AlarmRuleRepository;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleService {

    private static final int MAX_ASSIGNMENTS = 1000;
    private final AlarmRuleRepository rules;
    private final VehicleRepository vehicles;
    private final AlarmService alarms;
    private final AlarmRepository alarmConditions;

    public RuleService(
            AlarmRuleRepository rules,
            VehicleRepository vehicles,
            AlarmService alarms,
            AlarmRepository alarmConditions) {
        this.rules = rules;
        this.vehicles = vehicles;
        this.alarms = alarms;
        this.alarmConditions = alarmConditions;
    }

    public List<AlarmRule> findAll(DataScope scope) {
        return rules.findAll(scope);
    }

    public Optional<AlarmRule> findById(long id, DataScope scope) {
        return rules.findById(id, scope);
    }

    @Transactional
    public AlarmRule create(AuthorizedPrincipal caller, AlarmRule input) {
        Long tenantId = requireOwningTenant(caller, input);
        AlarmRule value = validate(input);
        long id = rules.insert(new AlarmRule(
                null, value.name(), value.type(), value.thresholdKph(), value.durationMinutes(),
                value.level(), value.enabled(), value.vehicleIds(), 0, tenantId, null, null));
        if (!value.vehicleIds().isEmpty()) {
            replaceVehicles(id, value.vehicleIds(), caller.scope());
        }
        return rules.findById(id, caller.scope()).orElseThrow();
    }

    @Transactional
    public Optional<AlarmRule> update(long id, AlarmRule input, DataScope scope) {
        if (rules.findById(id, scope).isEmpty()) return Optional.empty();
        AlarmRule value = validate(input);
        rules.update(id, value);
        replaceVehicles(id, value.vehicleIds(), scope);
        resetRuntime(id);
        return rules.findById(id, scope);
    }

    @Transactional
    public Optional<AlarmRule> setEnabled(long id, boolean enabled, DataScope scope) {
        Optional<AlarmRule> existing = rules.findById(id, scope);
        if (existing.isEmpty()) return Optional.empty();
        if (existing.get().enabled() == enabled) return existing;
        if (rules.setEnabled(id, enabled) == 0) return Optional.empty();
        resetRuntime(id);
        return rules.findById(id, scope);
    }

    @Transactional
    public Optional<AlarmRule> replaceVehicles(long id, List<String> rawDeviceIds, DataScope scope) {
        if (rules.findById(id, scope).isEmpty()) return Optional.empty();
        List<String> ids = normalizeVehicleIds(rawDeviceIds);
        for (String deviceId : ids) {
            if (!vehicles.visible(deviceId, scope)) {
                throw new IllegalArgumentException("规则只能分配已建档车辆");
            }
        }
        rules.replaceVehicles(id, ids);
        return rules.findById(id, scope);
    }

    @Transactional
    public boolean delete(long id, DataScope scope) {
        if (rules.findById(id, scope).isEmpty()) return false;
        resetRuntime(id);
        rules.deleteAssignments(id);
        return rules.delete(id) == 1;
    }

    /** @return 此位置新创建的规则告警数量。 */
    public int evaluate(
            String deviceId, String deviceTime, String receivedAt,
            double gcjLat, double gcjLng, Double speedKph, Boolean accOn) {
        int created = 0;
        for (AlarmRuleCandidate rule : rules.findEnabledForDevice(deviceId)) {
            created += evaluateRule(deviceId, deviceTime, receivedAt, gcjLat, gcjLng, speedKph, accOn, rule);
        }
        return created;
    }

    private int evaluateRule(
            String deviceId, String deviceTime, String receivedAt,
            double gcjLat, double gcjLng, Double speedKph, Boolean accOn, AlarmRuleCandidate rule) {
        String key = "rule:" + rule.id();
        return switch (rule.type()) {
            case SPEED_LIMIT -> {
                boolean over = speedKph != null && speedKph > rule.thresholdKph();
                yield over ? activate(deviceId, deviceTime, receivedAt, gcjLat, gcjLng, key, rule)
                           : deactivate(deviceId, key);
            }
            case IDLE_TIMEOUT -> {
                boolean idle = Boolean.TRUE.equals(accOn)
                        && (speedKph == null || speedKph < rule.thresholdKph());
                yield windowed(deviceId, deviceTime, receivedAt, gcjLat, gcjLng, key, rule, idle);
            }
            case FATIGUE_DRIVING -> {
                boolean driving = speedKph != null && speedKph > rule.thresholdKph();
                yield windowed(deviceId, deviceTime, receivedAt, gcjLat, gcjLng, key, rule, driving);
            }
        };
    }

    private int windowed(
            String deviceId, String deviceTime, String receivedAt,
            double gcjLat, double gcjLng, String key, AlarmRuleCandidate rule, boolean conditionMet) {
        if (!conditionMet) {
            rules.deleteWindow(rule.id(), deviceId);
            return deactivate(deviceId, key);
        }
        Optional<String> window = rules.findWindowStart(rule.id(), deviceId);
        if (window.isEmpty()) {
            rules.upsertWindowStart(rule.id(), deviceId, receivedAt);
            return 0;
        }
        if (durationExceeded(window.get(), receivedAt, rule.durationMinutes())) {
            return activate(deviceId, deviceTime, receivedAt, gcjLat, gcjLng, key, rule);
        }
        return 0;
    }

    private int activate(
            String deviceId, String deviceTime, String receivedAt,
            double gcjLat, double gcjLng, String key, AlarmRuleCandidate rule) {
        return alarms.setActive(deviceId, AlarmSource.RULE, key, definition(rule),
                receivedAt, deviceTime, receivedAt, gcjLat, gcjLng, null, null);
    }

    private int deactivate(String deviceId, String key) {
        alarms.setInactive(deviceId, AlarmSource.RULE, key);
        return 0;
    }

    private static AlarmDefinition definition(AlarmRuleCandidate rule) {
        return switch (rule.type()) {
            case SPEED_LIMIT -> AlarmDefinition.ruleSpeedLimit(rule.name(), rule.level());
            case IDLE_TIMEOUT -> AlarmDefinition.ruleIdleTimeout(rule.name(), rule.level());
            case FATIGUE_DRIVING -> AlarmDefinition.ruleFatigueDriving(rule.name(), rule.level());
        };
    }

    private static boolean durationExceeded(String start, String now, int minutes) {
        if (minutes <= 0) return true;
        try {
            Instant startInstant = Instant.parse(start);
            Instant nowInstant = Instant.parse(now);
            return Duration.between(startInstant, nowInstant).toMinutes() >= minutes;
        } catch (DateTimeParseException unparseable) {
            return false;
        }
    }

    private void resetRuntime(long id) {
        alarmConditions.deactivateByKey(AlarmSource.RULE, "rule:" + id);
        rules.deleteWindows(id);
    }

    private static Long requireOwningTenant(AuthorizedPrincipal caller, AlarmRule input) {
        if (!caller.platform()) {
            return caller.tenantId();
        }
        if (input == null || input.tenantId() == null) {
            throw new IllegalArgumentException("请先选择规则所属租户");
        }
        return input.tenantId();
    }

    private static AlarmRule validate(AlarmRule input) {
        if (input == null) throw new IllegalArgumentException("规则不能为空");
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("规则名称不合法");
        if (input.type() == null) throw new IllegalArgumentException("规则类型不能为空");
        if (!Double.isFinite(input.thresholdKph()) || input.thresholdKph() <= 0
                || input.thresholdKph() > 200) {
            throw new IllegalArgumentException("规则阈值不合法");
        }
        if (input.level() == null) throw new IllegalArgumentException("告警级别不能为空");
        int duration = input.durationMinutes();
        if (input.type() == AlarmRuleType.SPEED_LIMIT) {
            duration = 0;
        } else if (duration <= 0 || duration > 1440) {
            throw new IllegalArgumentException("持续时长不合法");
        }
        return new AlarmRule(input.id(), name, input.type(), input.thresholdKph(), duration,
                input.level(), input.enabled(), input.vehicleIds(), input.assignedVehicleCount(),
                input.tenantId(), input.createdAt(), input.updatedAt());
    }

    private static List<String> normalizeVehicleIds(List<String> values) {
        if (values == null) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("车辆编号不能为空");
            unique.add(raw.trim());
        }
        if (unique.size() > MAX_ASSIGNMENTS) throw new IllegalArgumentException("单规则车辆数量超过上限");
        return List.copyOf(unique);
    }
}
