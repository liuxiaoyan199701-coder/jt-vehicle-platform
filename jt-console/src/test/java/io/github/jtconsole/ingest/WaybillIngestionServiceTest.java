package io.github.jtconsole.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.repository.WaybillRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WaybillIngestionServiceTest {
    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
    private final WaybillIngestionService service = new WaybillIngestionService(waybills, ownership);

    @Test
    void storesRawContentWithOwnershipAndNormalizedTimes() {
        byte[] raw = "waybill".getBytes(StandardCharsets.UTF_8);
        MessageEnvelope envelope = envelope(Map.of(
                "rawBase64", Base64.getEncoder().encodeToString(raw),
                "length", raw.length,
                "deviceTime", "260820120000"));
        when(ownership.find("device-1"))
                .thenReturn(java.util.Optional.of(new DeviceOwnershipCache.Ownership(7, null)));
        when(waybills.insertIgnore(anyString(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString())).thenReturn(true);

        assertThat(service.handleIfWaybill(envelope)).isTrue();
        ArgumentCaptor<String> reported = ArgumentCaptor.forClass(String.class);
        verify(waybills).insertIgnore(anyString(),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("device-1"), reported.capture(),
                anyString(), org.mockito.ArgumentMatchers.eq(Base64.getEncoder().encodeToString(raw)),
                org.mockito.ArgumentMatchers.eq(raw.length), anyString());
        assertThat(reported.getValue()).isEqualTo("2026-08-20T12:00:00.000+08:00");
    }

    @Test
    void malformedBase64DoesNotEscapeAsAnUnexpectedException() {
        MessageEnvelope envelope = envelope(Map.of("rawBase64", "not base64!"));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.handleIfWaybill(envelope)))
                .isInstanceOf(InvalidEnvelopeException.class)
                .hasMessageContaining("rawBase64");
    }

    private static MessageEnvelope envelope(Map<String, Object> payload) {
        return new MessageEnvelope("waybill-event", "device-1", 0x0701L, 3,
                "JT/T 808-2019", Instant.parse("2026-08-20T04:00:00Z").toString(),
                "signal-1", "waybill", payload);
    }
}
