package io.github.jtconsole.domain;

/**
 * 一条网关连接诊断事件，tenantId 为空表示事件发生时设备尚未建档。
 *
 * @param detail 链路事件的结构化补充信息（JSON 文本），连接类事件为 null
 */
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
        String receivedAt,
        String detail) {

    /** 存量调用点的便利构造：不带结构化补充信息。 */
    public ConnectionEvent(
            long id, String eventId, String deviceId, Long tenantId, String kind,
            Integer reasonCode, String reason, String remoteAddr, int repeatCount,
            String eventTime, String receivedAt) {
        this(id, eventId, deviceId, tenantId, kind, reasonCode, reason, remoteAddr,
                repeatCount, eventTime, receivedAt, null);
    }
}
