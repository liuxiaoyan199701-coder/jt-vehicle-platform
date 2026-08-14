package io.github.jtconsole.operations;

import io.github.jtconsole.domain.AlarmDefinition;
import io.github.jtconsole.domain.AlarmSource;
import io.github.jtconsole.domain.Geofence;
import io.github.jtconsole.domain.GeofenceCandidate;
import io.github.jtconsole.geo.CoordTransform;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.GeofenceRepository;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GeofenceService {

    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final int MAX_ASSIGNMENTS = 1000;
    private final GeofenceRepository geofences;
    private final VehicleRepository vehicles;
    private final AlarmRepository alarms;
    private final AlarmService alarmService;

    public GeofenceService(
            GeofenceRepository geofences,
            VehicleRepository vehicles,
            AlarmRepository alarms,
            AlarmService alarmService) {
        this.geofences = geofences;
        this.vehicles = vehicles;
        this.alarms = alarms;
        this.alarmService = alarmService;
    }

    public List<Geofence> findAll(DataScope scope) {
        return geofences.findAll(scope);
    }

    public Optional<Geofence> findById(long id, DataScope scope) {
        return geofences.findById(id, scope);
    }

    @Transactional
    public Geofence create(AuthorizedPrincipal caller, Geofence input) {
        Long tenantId = requireOwningTenant(caller, input);
        Geofence value = validate(input);
        long id = geofences.insert(new Geofence(
                null, value.name(), value.centerGcjLat(), value.centerGcjLng(),
                value.radiusMeters(), value.color(), value.enabled(), value.alertOnEnter(),
                value.alertOnExit(), value.speedLimitKph(), value.vehicleIds(),
                0, tenantId, null, null));
        if (!value.vehicleIds().isEmpty()) {
            replaceVehicles(id, value.vehicleIds(), caller.scope());
        }
        return geofences.findById(id, caller.scope()).orElseThrow();
    }

    @Transactional
    public Optional<Geofence> update(long id, Geofence input, DataScope scope) {
        Optional<Geofence> existing = geofences.findById(id, scope);
        if (existing.isEmpty()) return Optional.empty();
        Geofence value = validate(input);
        geofences.update(id, value);
        replaceVehicles(id, value.vehicleIds(), scope);
        if (runtimeRulesChanged(existing.get(), value)) {
            resetRuntime(id, false);
        }
        return geofences.findById(id, scope);
    }

    @Transactional
    public Optional<Geofence> setEnabled(long id, boolean enabled, DataScope scope) {
        Optional<Geofence> existing = geofences.findById(id, scope);
        if (existing.isEmpty()) return Optional.empty();
        if (existing.get().enabled() == enabled) return existing;
        if (geofences.setEnabled(id, enabled) == 0) return Optional.empty();
        resetRuntime(id, false);
        return geofences.findById(id, scope);
    }

    @Transactional
    public Optional<Geofence> replaceVehicles(long id, List<String> rawDeviceIds, DataScope scope) {
        if (geofences.findById(id, scope).isEmpty()) return Optional.empty();
        List<String> ids = normalizeVehicleIds(rawDeviceIds);
        for (String deviceId : ids) {
            // 范围外的车辆对调用者就该是「未建档」，不能因为它全局存在就允许绑定。
            if (!vehicles.visible(deviceId, scope)) {
                throw new IllegalArgumentException("围栏只能分配已建档车辆");
            }
        }
        Set<String> retained = new LinkedHashSet<>(geofences.assignedVehicleIds(id));
        retained.retainAll(ids);
        Set<String> removed = new LinkedHashSet<>(geofences.assignedVehicleIds(id));
        removed.removeAll(retained);
        geofences.replaceVehicles(id, ids);
        removed.forEach(deviceId -> alarms.deleteGeofenceCondition(id, deviceId));
        return geofences.findById(id, scope);
    }

    @Transactional
    public boolean delete(long id, DataScope scope) {
        if (geofences.findById(id, scope).isEmpty()) return false;
        resetRuntime(id, true);
        geofences.deleteAssignments(id);
        return geofences.delete(id) == 1;
    }

    /** 围栏归属租户：租户用户即自己的租户，平台管理员必须显式指定。 */
    private static Long requireOwningTenant(AuthorizedPrincipal caller, Geofence input) {
        if (!caller.platform()) {
            return caller.tenantId();
        }
        if (input == null || input.tenantId() == null) {
            throw new IllegalArgumentException("请先选择围栏所属租户");
        }
        return input.tenantId();
    }

    /** @return 此位置新创建的围栏告警数量。 */
    public int evaluate(
            String deviceId, String deviceTime, String receivedAt,
            double gcjLat, double gcjLng, Double speedKph) {
        int created = 0;
        String occurredAt = receivedAt;
        for (GeofenceCandidate fence : geofences.findEnabledForDevice(deviceId)) {
            boolean inside = CoordTransform.distanceMeters(
                    gcjLat, gcjLng, fence.centerGcjLat(), fence.centerGcjLng()) <= fence.radiusMeters();
            Optional<Boolean> previous = geofences.presence(fence.id(), deviceId);
            if (previous.isPresent() && previous.get() != inside) {
                if (inside && fence.alertOnEnter()) {
                    created += alarmService.createDiscrete(deviceId, AlarmDefinition.geofenceEnter(),
                            occurredAt, deviceTime, receivedAt, gcjLat, gcjLng, fence.id(), fence.name());
                } else if (!inside && fence.alertOnExit()) {
                    created += alarmService.createDiscrete(deviceId, AlarmDefinition.geofenceExit(),
                            occurredAt, deviceTime, receivedAt, gcjLat, gcjLng, fence.id(), fence.name());
                }
            }
            geofences.upsertPresence(fence.id(), deviceId, inside, occurredAt);

            String speedKey = "overspeed:" + fence.id();
            if (inside && fence.speedLimitKph() != null && speedKph == null) {
                continue;
            }
            boolean overspeed = inside && fence.speedLimitKph() != null
                    && speedKph > fence.speedLimitKph();
            if (overspeed) {
                created += alarmService.setActive(deviceId, AlarmSource.GEOFENCE, speedKey,
                        AlarmDefinition.geofenceOverspeed(), occurredAt, deviceTime, receivedAt,
                        gcjLat, gcjLng, fence.id(), fence.name());
            } else {
                alarmService.setInactive(deviceId, AlarmSource.GEOFENCE, speedKey);
            }
        }
        return created;
    }

    private void resetRuntime(long id, boolean deleting) {
        geofences.deletePresence(id);
        if (deleting) alarms.deleteGeofenceConditions(id);
        else alarms.deactivateGeofenceConditions(id);
    }

    private static boolean runtimeRulesChanged(Geofence previous, Geofence next) {
        return previous.enabled() != next.enabled()
                || Double.compare(previous.centerGcjLat(), next.centerGcjLat()) != 0
                || Double.compare(previous.centerGcjLng(), next.centerGcjLng()) != 0
                || Double.compare(previous.radiusMeters(), next.radiusMeters()) != 0
                || !java.util.Objects.equals(previous.speedLimitKph(), next.speedLimitKph());
    }

    private static Geofence validate(Geofence input) {
        if (input == null) throw new IllegalArgumentException("围栏不能为空");
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("围栏名称不合法");
        if (!Double.isFinite(input.centerGcjLat()) || input.centerGcjLat() < -90 || input.centerGcjLat() > 90
                || !Double.isFinite(input.centerGcjLng()) || input.centerGcjLng() < -180 || input.centerGcjLng() > 180) {
            throw new IllegalArgumentException("围栏中心坐标不合法");
        }
        if (!Double.isFinite(input.radiusMeters()) || input.radiusMeters() <= 0
                || input.radiusMeters() > 1_000_000) {
            throw new IllegalArgumentException("围栏半径不合法");
        }
        String color = input.color() == null ? "" : input.color().trim();
        if (!COLOR.matcher(color).matches()) throw new IllegalArgumentException("围栏颜色不合法");
        if (input.speedLimitKph() != null
                && (!Double.isFinite(input.speedLimitKph()) || input.speedLimitKph() <= 0
                || input.speedLimitKph() > 500)) {
            throw new IllegalArgumentException("围栏限速不合法");
        }
        return new Geofence(input.id(), name, input.centerGcjLat(), input.centerGcjLng(),
                input.radiusMeters(), color.toUpperCase(), input.enabled(), input.alertOnEnter(),
                input.alertOnExit(), input.speedLimitKph(), input.vehicleIds(),
                input.assignedVehicleCount(), input.tenantId(),
                input.createdAt(), input.updatedAt());
    }

    private static List<String> normalizeVehicleIds(List<String> values) {
        if (values == null) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("车辆编号不能为空");
            unique.add(raw.trim());
        }
        if (unique.size() > MAX_ASSIGNMENTS) throw new IllegalArgumentException("单围栏车辆数量超过上限");
        return List.copyOf(unique);
    }
}
