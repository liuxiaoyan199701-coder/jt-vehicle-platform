package io.github.jtconsole.operations;

import io.github.jtconsole.domain.AlarmDefinition;
import io.github.jtconsole.domain.AlarmEvent;
import io.github.jtconsole.domain.AlarmSource;
import io.github.jtconsole.domain.AlarmStatus;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.AlarmRepository.ConditionState;
import io.github.jtconsole.repository.DailyStatRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlarmService {

    private final AlarmRepository alarms;
    private final DailyStatRepository dailyStats;
    private final BusinessDateService dates;

    public AlarmService(
            AlarmRepository alarms, DailyStatRepository dailyStats, BusinessDateService dates) {
        this.alarms = alarms;
        this.dailyStats = dailyStats;
        this.dates = dates;
    }

    /** 缺失 alarmFlags 表示状态未知，不得清除任何已有报警。 */
    public int syncProtocol(
            String deviceId,
            Map<String, Object> alarmFlags,
            boolean flagsPresent,
            String occurredAt,
            String deviceTime,
            String receivedAt,
            Double gcjLat,
            Double gcjLng) {
        if (!flagsPresent) return 0;

        int created = 0;
        for (Map.Entry<String, AlarmDefinition> entry : AlarmDefinition.protocolDefinitions().entrySet()) {
            Object value = alarmFlags.get(entry.getKey());
            if (Boolean.TRUE.equals(value)) {
                created += setActive(deviceId, AlarmSource.PROTOCOL, entry.getKey(), entry.getValue(),
                        occurredAt, deviceTime, receivedAt, gcjLat, gcjLng, null, null);
            } else if (Boolean.FALSE.equals(value)) {
                alarms.deactivateCondition(deviceId, AlarmSource.PROTOCOL, entry.getKey());
            }
        }
        return created;
    }

    public int setActive(
            String deviceId,
            AlarmSource source,
            String key,
            AlarmDefinition definition,
            String occurredAt,
            String deviceTime,
            String receivedAt,
            Double gcjLat,
            Double gcjLng,
            Long geofenceId,
            String geofenceName) {
        Optional<ConditionState> current = alarms.findCondition(deviceId, source, key);
        if (current.isPresent() && current.get().active() && current.get().alarmEventId() != null) {
            alarms.touchCondition(deviceId, source, key, current.get().alarmEventId(), occurredAt);
            return 0;
        }
        long id = alarms.create(deviceId, definition, source, occurredAt,
                gcjLat, gcjLng, geofenceId, geofenceName);
        alarms.activateCondition(deviceId, source, key, id, geofenceId, occurredAt);
        LocalDate date = dates.resolve(deviceTime, receivedAt);
        dailyStats.incrementAlarm(deviceId, date.toString(), 1, dates.now().toString());
        return 1;
    }

    public void setInactive(String deviceId, AlarmSource source, String key) {
        alarms.deactivateCondition(deviceId, source, key);
    }

    public int createDiscrete(
            String deviceId,
            AlarmDefinition definition,
            String occurredAt,
            String deviceTime,
            String receivedAt,
            double gcjLat,
            double gcjLng,
            long geofenceId,
            String geofenceName) {
        alarms.create(deviceId, definition, AlarmSource.GEOFENCE, occurredAt,
                gcjLat, gcjLng, geofenceId, geofenceName);
        LocalDate date = dates.resolve(deviceTime, receivedAt);
        dailyStats.incrementAlarm(deviceId, date.toString(), 1, dates.now().toString());
        return 1;
    }

    public int activeCount(String deviceId) {
        return alarms.countActive(deviceId);
    }

    @Transactional
    public ActionResult acknowledge(long id, String note, String operator) {
        AlarmEvent event = alarms.findById(id).orElse(null);
        if (event == null) return ActionResult.NOT_FOUND;
        if (event.status() != AlarmStatus.OPEN) return ActionResult.INVALID_STATE;
        String at = Instant.now().toString();
        return alarms.acknowledge(id, requireNote(note), operator, at) == 1
                ? ActionResult.UPDATED : ActionResult.INVALID_STATE;
    }

    @Transactional
    public ActionResult close(long id, String note, String operator) {
        AlarmEvent event = alarms.findById(id).orElse(null);
        if (event == null) return ActionResult.NOT_FOUND;
        if (event.status() == AlarmStatus.CLOSED) return ActionResult.INVALID_STATE;
        String at = Instant.now().toString();
        return alarms.close(id, requireNote(note), operator, at) == 1
                ? ActionResult.UPDATED : ActionResult.INVALID_STATE;
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) throw new IllegalArgumentException("处置备注不能为空");
        String trimmed = note.trim();
        if (trimmed.length() > 500) throw new IllegalArgumentException("处置备注不能超过 500 字");
        return trimmed;
    }

    public enum ActionResult { UPDATED, NOT_FOUND, INVALID_STATE }
}
