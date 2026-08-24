package io.github.jtconsole.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.domain.ConnectionEvent;
import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.repository.DeviceLogRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 上下线双写。connection_event 仍是连接事件的权威源，日志库那份只为「单表可查完整时间线」。
 */
class ConnectionEventDeviceLogMirrorTest {

    private final ConnectionEventRepository events = mock(ConnectionEventRepository.class);
    private final DeviceLogRepository logs = mock(DeviceLogRepository.class);
    private final DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
    private ConnectionEventIngestionService service;

    @BeforeEach
    void setUp() {
        when(ownership.find("known")).thenReturn(
                Optional.of(new DeviceOwnershipCache.Ownership(7L, null)));
        service = new ConnectionEventIngestionService(events, ownership, logs);
    }

    @Test
    void anOnlineEventAlsoLandsOnTheDeviceLogTimeline() {
        service.handle(envelope("conn-1", "known", Map.of(
                "kind", "CONNECTED", "reason", "对端建立连接", "eventTime", "2026-08-24T01:02:03Z")));

        ArgumentCaptor<DeviceLog> captor = ArgumentCaptor.forClass(DeviceLog.class);
        verify(logs).insertIgnore(captor.capture());
        DeviceLog mirrored = captor.getValue();
        assertThat(mirrored.direction()).isEqualTo("CONNECTION");
        assertThat(mirrored.deviceId()).isEqualTo("known");
        assertThat(mirrored.tenantId()).isEqualTo(7L);
        assertThat(mirrored.summary()).contains("CONNECTED").contains("对端建立连接");
        assertThat(mirrored.rawHex()).isNull();
        assertThat(mirrored.msgId()).isNull();
        assertThat(mirrored.logTime()).isEqualTo("2026-08-24T09:02:03.000+08:00");
        // 后缀避免与未来可能从网关直发的日志信封撞上唯一键。
        assertThat(mirrored.eventId()).isEqualTo("conn-1:device-log");
    }

    @Test
    void aCommandOutcomeCarriesItsStructuredDetailIntoTheTimeline() {
        service.handle(envelope("cmd-1", "known", Map.of(
                "kind", "COMMAND_RESULT", "reason", "0x8801 终端拒绝指令",
                "eventTime", "2026-08-24T01:02:03Z",
                "detail", Map.of("commandMsgId", "0x8801", "outcome", "REJECTED"))));

        ArgumentCaptor<DeviceLog> captor = ArgumentCaptor.forClass(DeviceLog.class);
        verify(logs).insertIgnore(captor.capture());
        assertThat(captor.getValue().parsedJson()).contains("0x8801").contains("REJECTED");
    }

    /** 日志时间线缺一格，远不如把连接投影拖垮严重——写失败只能是一条 warn。 */
    @Test
    void aFailingMirrorNeverBreaksTheAuthoritativeConnectionProjection() {
        doThrow(new IllegalStateException("device log database is locked"))
                .when(logs).insertIgnore(any());

        boolean handled = service.handle(envelope("conn-2", "known", Map.of(
                "kind", "DISCONNECTED", "eventTime", "2026-08-24T01:02:03Z")));

        assertThat(handled).isTrue();
        verify(events).insertIgnore(any(ConnectionEvent.class));
    }

    private static MessageEnvelope envelope(
            String eventId, String deviceId, Map<String, Object> payload) {
        return new MessageEnvelope(eventId, deviceId, 0x10001L, 1, "JT/T 808-2019",
                "2026-08-24T01:02:03Z", "signal-1", "connection", payload);
    }
}
