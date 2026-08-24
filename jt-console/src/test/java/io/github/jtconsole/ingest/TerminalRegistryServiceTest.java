package io.github.jtconsole.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.jtconsole.domain.Terminal;
import io.github.jtconsole.repository.EventRepository;
import io.github.jtconsole.repository.TerminalRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TerminalRegistryServiceTest {

    private static final String MOBILE = "138000000000";
    private static final String TERMINAL = "1380000";

    private final TerminalRepository terminals = mock(TerminalRepository.class);
    private final TerminalRegistryService service = new TerminalRegistryService(terminals);

    /**
     * <b>契约：两个 deviceId 同名不同义，各归各位。</b>
     *
     * <p>信封的 deviceId 是手机号（全平台主键），payload 里的 deviceId 是终端自报的终端 ID。
     * 写反了不抛异常、接口照样 200，唯一症状是台账与 vehicle 永远 join 不上、
     * 页面上每台设备都显示「未建档」。2026-08-24 的 connection_event 就栽在同一个坑上。
     */
    @Test
    void theEnvelopeMobileNumberIsTheKeyAndThePayloadDeviceIdIsOnlyTheTerminalId() {
        service.handle(registrationEnvelope());

        Terminal stored = captured();
        assertThat(stored.deviceId())
                .as("台账主键必须是信封的手机号，取成终端 ID 会让它与车辆档案永远对不上")
                .isEqualTo(MOBILE);
        assertThat(stored.terminalId())
                .as("payload.deviceId 是终端自报的编号，只能落到 terminal_id 这个附加列")
                .isEqualTo(TERMINAL);
    }

    @Test
    void everythingTheTerminalReportedIsKept() {
        service.handle(registrationEnvelope());

        Terminal stored = captured();
        assertThat(stored.makerId()).isEqualTo("JT");
        assertThat(stored.deviceModel()).isEqualTo("SIMULATOR");
        assertThat(stored.provinceId()).isEqualTo(31);
        assertThat(stored.cityId()).isEqualTo(100);
        assertThat(stored.reportedPlate()).isEqualTo("TEST001");
        assertThat(stored.reportedColor()).isEqualTo(1);
        assertThat(stored.protocolVersion()).isEqualTo("JT/T 808-2019/1");
        assertThat(stored.lastResult()).isEqualTo("注册");
        // 时间统一换算到全平台口径，否则清单按时间排序会在两种格式之间错乱。
        assertThat(stored.firstSeenAt()).isEqualTo("2026-08-24T09:02:03.000+08:00");
        assertThat(stored.lastSeenAt()).isEqualTo(stored.firstSeenAt());
    }

    @Test
    void anAuthenticationEnvelopeIsAlsoTaken() {
        assertThat(service.handle(envelope("authentication", Map.of()))).isTrue();

        assertThat(captured().lastResult()).isEqualTo("鉴权");
    }

    @Test
    void otherEnvelopeTypesAreLeftAlone() {
        assertThat(service.handle(envelope("location", Map.of()))).isFalse();
        assertThat(service.handle(envelope("device_log", Map.of()))).isFalse();
        verify(terminals, never()).upsert(any());
    }

    /** 台账缺一行远不如把位置投影拖垮严重——写失败只能是一条 warn。 */
    @Test
    void aFailingWriteNeverBreaksTheRestOfTheIngestionChain() {
        doThrow(new IllegalStateException("database is locked")).when(terminals).upsert(any());
        EventRepository events = mock(EventRepository.class);
        org.mockito.Mockito.when(events.markProcessed(anyString())).thenReturn(true);
        LocationService locations = mock(LocationService.class);
        org.mockito.Mockito.when(locations.handle(any()))
                .thenReturn(new LocationHandlingResult("touched", null));
        EventIngestionService ingestion = new EventIngestionService(
                events, locations, mock(MediaIngestionService.class),
                mock(DriverIdentityIngestionService.class), mock(WaybillIngestionService.class),
                mock(RecordingUploadIngestionService.class),
                mock(ConnectionEventIngestionService.class), null, service);

        ingestion.ingest(registrationEnvelope());

        // 台账炸了，但常规链路照常走完。
        verify(locations).handle(any());
    }

    /** 注册鉴权是低频事件，照常走幂等表；不像报文日志那样需要早分支绕开业务库。 */
    @Test
    void registrationStillGoesThroughTheRegularIdempotencyPath() {
        EventRepository events = mock(EventRepository.class);
        org.mockito.Mockito.when(events.markProcessed(anyString())).thenReturn(true);
        LocationService locations = mock(LocationService.class);
        org.mockito.Mockito.when(locations.handle(any()))
                .thenReturn(new LocationHandlingResult("touched", null));
        EventIngestionService ingestion = new EventIngestionService(
                events, locations, mock(MediaIngestionService.class),
                mock(DriverIdentityIngestionService.class), mock(WaybillIngestionService.class),
                mock(RecordingUploadIngestionService.class),
                mock(ConnectionEventIngestionService.class), null, service);

        ingestion.ingest(registrationEnvelope());

        verify(events).markProcessed("evt-1");
        verify(terminals).upsert(any());
    }

    private Terminal captured() {
        ArgumentCaptor<Terminal> captor = ArgumentCaptor.forClass(Terminal.class);
        verify(terminals).upsert(captor.capture());
        return captor.getValue();
    }

    /** 字段形态与网关 {@code ProtocolPayloadMapper} 为 0x0100 生成的完全一致（生产实测）。 */
    private static MessageEnvelope registrationEnvelope() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cityId", 100);
        payload.put("deviceId", TERMINAL);
        payload.put("deviceModel", "SIMULATOR");
        payload.put("makerId", "JT");
        payload.put("plateColor", 1);
        payload.put("plateNo", "TEST001");
        payload.put("provinceId", 31);
        return envelope("register", payload);
    }

    private static MessageEnvelope envelope(String type, Map<String, Object> payload) {
        return new MessageEnvelope("evt-1", MOBILE, 0x0100L, 144, "JT/T 808-2019/1",
                "2026-08-24T01:02:03Z", "signal-1", type, payload);
    }
}
