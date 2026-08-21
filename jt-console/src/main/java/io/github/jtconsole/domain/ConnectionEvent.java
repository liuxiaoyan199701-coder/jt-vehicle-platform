package io.github.jtconsole.domain;

/** 一条网关连接诊断事件，tenantId 为空表示事件发生时设备尚未建档。 */
public record ConnectionEvent(
        long id,
        String eventId,
        String deviceId,
        Long tenantId,
        String kind,
        Integer reasonCode,
        String reason,
        String remoteAddr,
        int repeatCount,
        String eventTime,
        String receivedAt) {
}
