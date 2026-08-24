package io.github.jtconsole.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.repository.DeviceLogRepository;
import io.github.jtconsole.repository.EventRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeviceLogIngestionServiceTest {

    private final DeviceLogRepository logs = mock(DeviceLogRepository.class);
    private final DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
    private DeviceLogIngestionService service;

    @BeforeEach
    void setUp() {
        when(ownership.find(anyString())).thenReturn(Optional.empty());
        when(ownership.find("known")).thenReturn(
                Optional.of(new DeviceOwnershipCache.Ownership(7L, 3L)));
        service = new DeviceLogIngestionService(logs, ownership);
    }

    @Test
    void anUplinkEnvelopeBecomesARowWithBothTheRawFrameAndTheParsedBody() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("direction", "UP");
        payload.put("msgIdHex", "0x0200");
        payload.put("serialNo", "7");
        payload.put("summary", "位置信息汇报");
        payload.put("rawHex", "7e0200");
        payload.put("parsedJson", "{\"speedKph\":6.0}");
        payload.put("decodeError", "0");
        payload.put("truncated", "0");
        payload.put("logTime", "2026-08-24T01:02:03Z");

        assertThat(service.handle(envelope("evt-1", "known", 0x0200L, payload))).isTrue();

        DeviceLog row = captured();
        assertThat(row.eventId()).isEqualTo("evt-1");
        assertThat(row.deviceId()).isEqualTo("known");
        assertThat(row.tenantId()).isEqualTo(7L);
        assertThat(row.direction()).isEqualTo("UP");
        assertThat(row.msgId()).isEqualTo(0x0200);
        assertThat(row.msgIdHex()).isEqualTo("0x0200");
        assertThat(row.serialNo()).isEqualTo(7);
        assertThat(row.rawHex()).isEqualTo("7e0200");
        assertThat(row.parsedJson()).isEqualTo("{\"speedKph\":6.0}");
        assertThat(row.decodeError()).isFalse();
        assertThat(row.truncated()).isFalse();
        assertThat(row.instanceId()).isEqualTo("signal-1");
        // 时间统一换算到全平台口径，否则字典序范围查询会在两种格式之间失效。
        assertThat(row.logTime()).isEqualTo("2026-08-24T09:02:03.000+08:00");
    }

    @Test
    void anUnprofiledDeviceIsRecordedWithoutATenantRatherThanBeingDropped() {
        assertThat(service.handle(envelope("evt-2", "stranger", 0x0200L, Map.of(
                "direction", "UP", "logTime", "2026-08-24T01:02:03Z")))).isTrue();

        assertThat(captured().tenantId()).isNull();
    }

    /** 解码失败时信封里的 messageId 是「不知道」而不是 0x0000，记 NULL 才不会污染按消息 ID 的筛选。 */
    @Test
    void aDecodeFailureKeepsItsRawBytesAndClaimsNoMessageId() {
        assertThat(service.handle(envelope("evt-3", "stranger", 0L, Map.of(
                "direction", "UP", "rawHex", "7edeadbeef7e", "decodeError", "1",
                "summary", "解码失败：IndexOutOfBoundsException",
                "logTime", "2026-08-24T01:02:03Z")))).isTrue();

        DeviceLog row = captured();
        assertThat(row.msgId()).isNull();
        assertThat(row.msgIdHex()).isNull();
        assertThat(row.decodeError()).isTrue();
        assertThat(row.rawHex()).isEqualTo("7edeadbeef7e");
        assertThat(row.parsedJson()).isNull();
    }

    @Test
    void theTruncationFlagSurvivesTheWireAsAString() {
        service.handle(envelope("evt-4", "known", 0x0200L, Map.of(
                "direction", "DOWN", "truncated", "1", "logTime", "2026-08-24T01:02:03Z")));

        assertThat(captured().truncated()).isTrue();
        assertThat(captured().direction()).isEqualTo("DOWN");
    }

    @Test
    void otherEnvelopeTypesAreLeftToTheRegularProjections() {
        assertThat(service.handle(envelope("evt-5", "known", 0x0200L, Map.of(), "location"))).isFalse();
        verify(logs, never()).insertIgnore(any());
    }

    /**
     * 日志信封在 markProcessed <b>之前</b>分流：走原链路等于让日志流量在业务库上再写一次幂等表，
     * 而日志库物理隔离的全部意义就是不碰业务库那把唯一的写锁。
     */
    @Test
    void deviceLogEnvelopesNeverTouchTheBusinessDatabase() {
        EventRepository events = mock(EventRepository.class);
        LocationService locations = mock(LocationService.class);
        EventIngestionService ingestion = new EventIngestionService(
                events, locations, mock(MediaIngestionService.class),
                mock(DriverIdentityIngestionService.class), mock(WaybillIngestionService.class),
                mock(RecordingUploadIngestionService.class),
                mock(ConnectionEventIngestionService.class), service);

        IngestionResult result = ingestion.ingest(envelope("evt-6", "known", 0x0200L, Map.of(
                "direction", "UP", "logTime", "2026-08-24T01:02:03Z")));

        assertThat(result.outcome()).isEqualTo("device-log");
        verify(events, never()).markProcessed(anyString());
        verify(locations, never()).handle(any());
        verify(logs).insertIgnore(any());
    }

    private DeviceLog captured() {
        ArgumentCaptor<DeviceLog> captor = ArgumentCaptor.forClass(DeviceLog.class);
        verify(logs, org.mockito.Mockito.atLeastOnce()).insertIgnore(captor.capture());
        return captor.getValue();
    }

    private static MessageEnvelope envelope(
            String eventId, String deviceId, long messageId, Map<String, Object> payload) {
        return envelope(eventId, deviceId, messageId, payload, "device_log");
    }

    private static MessageEnvelope envelope(
            String eventId, String deviceId, long messageId,
            Map<String, Object> payload, String type) {
        return new MessageEnvelope(eventId, deviceId, messageId, 7, "JT/T 808-2019",
                "2026-08-24T01:02:03Z", "signal-1", type, payload);
    }
}
