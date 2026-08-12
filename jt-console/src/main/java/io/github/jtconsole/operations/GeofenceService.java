package io.github.jtconsole.operations;

import io.github.jtconsole.domain.AlarmDefinition;
import io.github.jtconsole.domain.AlarmSource;
import io.github.jtconsole.domain.Geofence;
import io.github.jtconsole.domain.GeofenceCandidate;
import io.github.jtconsole.geo.CoordTransform;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.GeofenceRepository;
import io.github.jtconsole.repository.VehicleRepository;
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

    public List<Geofence> findAll() {
        return geofences.findAll();
    }

    public Optional<Geofence> findById(long id) {
        return geofences.findById(id);
    }

    @Transactional
    public Geofence create(Geofence input) {
        Geofence value = validate(input);
        long id = geofences.insert(value);
        if (!value.vehicleIds().isEmpty()) replaceVehicles(id, value.vehicleIds());
        return geofences.findById(id).orElseThrow();
    }

    @Transactional
    public Optional<Geofence> update(long id, Geofence input) {
        Optional<Geofence> existing = geofences.findById(id);
        if (existing.isEmpty()) return Optional.empty();
        Geofence value = validate(input);
        geofences.update(id, value);
        replaceVehicles(id, value.vehicleIds());
        if (runtimeRulesChanged(existing.get(), value)) {
            resetRuntime(id, false);
        }
        return geofences.findById(id);
    }

    @Transactional
    public Optional<Geofence> setEnabled(long id, boolean enabled) {
        Optional<Geofence> existing = geofences.findById(id);
        if (existing.isEmpty()) return Optional.empty();
        if (existing.get().enabled() == enabled) return existing;
        if (geofences.setEnabled(id, enabled) == 0) return Optional.empty();
        resetRuntime(id, false);
        return geofences.findById(id);
    }

    @Transactional
    public Optional<Geofence> replaceVehicles(long id, List<String> rawDeviceIds) {
        if (geofences.findById(id).isEmpty()) return Optional.empty();
        List<String> ids = normalizeVehicleIds(rawDeviceIds);
        for (String deviceId : ids) {
            if (!vehicles.exists(deviceId)) {
                throw new IllegalArgumentException("围栏只能分配已建档车辆");
            }
        }
        Set<String> retained = new LinkedHashSet<>(geofences.assignedVehicleIds(id));
        retained.retainAll(ids);
        Set<String> removed = new LinkedHashSet<>(geofences.assignedVehicleIds(id));
        removed.removeAll(retained);
        geofences.replaceVehicles(id, ids);
        removed.forEach(deviceId -> alarms.deleteGeofenceCondition(id, deviceId));
        return geofences.findById(id);
    }

    @Transactional
    public boolean delete(long id) {
        if (geofences.findById(id).isEmpty()) return false;
        resetRuntime(id, true);
        geofences.deleteAssignments(id);
        return geofences.delete(id) == 1;
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
                input.assignedVehicleCount(), input.createdAt(), input.updatedAt());
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
