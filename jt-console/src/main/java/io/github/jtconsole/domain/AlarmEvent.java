package io.github.jtconsole.domain;

/** 可检索和处置的告警历史。 */
public record AlarmEvent(
        long id,
        String deviceId,
        String plateNo,
        String type,
        String title,
        AlarmSource source,
        AlarmLevel level,
        AlarmStatus status,
        String occurredAt,
        String lastOccurredAt,
        Double gcjLat,
        Double gcjLng,
        Long geofenceId,
        String geofenceName,
        String acknowledgedAt,
        String acknowledgedBy,
        String acknowledgeNote,
        String closedAt,
        String closedBy,
        String closeNote) {
}
