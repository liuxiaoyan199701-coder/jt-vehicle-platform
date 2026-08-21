package io.github.jtconsole.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.domain.Driver;
import io.github.jtconsole.domain.DriverIdentityEvent;
import io.github.jtconsole.repository.DriverRepository;
import io.github.jtconsole.security.DataScope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DriverIdentityIngestionServiceTest {

    @Mock
    private DriverRepository drivers;

    @InjectMocks
    private DriverIdentityIngestionService service;

    private static MessageEnvelope envelope(String eventId, Map<String, Object> payload) {
        return new MessageEnvelope(eventId, "device-1", 0x0702L, 1, "2019",
                "2026-08-11T08:00:00Z", "signal-1", "other", payload);
    }

    private static Map<String, Object> payload(int status, int cardStatus, String licenseNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("cardStatus", cardStatus);
        payload.put("dateTime", "2026-08-11T08:00:00");
        payload.put("name", "张三");
        payload.put("licenseNo", licenseNo);
        payload.put("institution", "某发证机关");
        payload.put("licenseValidPeriod", "20260830");
        payload.put("idCard", "110101199001011234");
        return payload;
    }

    @Test
    void cardInOpensSessionAndNormalizesTimestamps() {
        when(drivers.findByLicenseNo(eq("LIC123"), any(DataScope.class)))
                .thenReturn(Optional.of(new Driver(1L, "张三", "110101199001011234", "LIC123",
                        "某发证机关", "2026-08-30", "13800000000", null, null, 1L, null, null)));
        when(drivers.insertIdentityEvent(any())).thenReturn(true);

        assertTrue(service.handleIfDriverIdentity(envelope("e-1", payload(0, 0, "LIC123"))));

        ArgumentCaptor<DriverIdentityEvent> event = ArgumentCaptor.forClass(DriverIdentityEvent.class);
        verify(drivers).insertIdentityEvent(event.capture());
        assertEquals("2026-08-11T08:00:00.000+08:00", event.getValue().deviceTime());
        assertEquals("2026-08-11T16:00:00.000+08:00", event.getValue().receivedAt());
        assertEquals(1L, event.getValue().driverId());

        verify(drivers).closeOpenSession("device-1", "2026-08-11T08:00:00.000+08:00");
        verify(drivers).openSession("device-1", 1L, "张三", "LIC123",
                "2026-08-11T08:00:00.000+08:00", "CARD");
    }

    @Test
    void cardOutClosesSession() {
        when(drivers.insertIdentityEvent(any())).thenReturn(true);

        service.handleIfDriverIdentity(envelope("e-2", payload(1, 0, null)));

        verify(drivers).closeOpenSession("device-1", "2026-08-11T08:00:00.000+08:00");
        verify(drivers, never()).openSession(anyString(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void cardReadFailureOnlyRecordsEvent() {
        when(drivers.insertIdentityEvent(any())).thenReturn(true);

        service.handleIfDriverIdentity(envelope("e-3", payload(0, 2, "LIC123")));

        verify(drivers, never()).openSession(anyString(), any(), anyString(), anyString(), anyString(), anyString());
        verify(drivers, never()).closeOpenSession(anyString(), anyString());
    }

    @Test
    void unmatchedLicenseLeavesDriverIdNull() {
        when(drivers.findByLicenseNo(eq("UNKNOWN"), any(DataScope.class))).thenReturn(Optional.empty());
        when(drivers.insertIdentityEvent(any())).thenReturn(true);

        service.handleIfDriverIdentity(envelope("e-4", payload(0, 0, "UNKNOWN")));

        ArgumentCaptor<DriverIdentityEvent> event = ArgumentCaptor.forClass(DriverIdentityEvent.class);
        verify(drivers).insertIdentityEvent(event.capture());
        assertEquals(null, event.getValue().driverId());
        verify(drivers).openSession("device-1", null, "张三", "UNKNOWN",
                "2026-08-11T08:00:00.000+08:00", "CARD");
    }

    @Test
    void duplicateEventDoesNotTouchSessions() {
        when(drivers.insertIdentityEvent(any())).thenReturn(false);

        service.handleIfDriverIdentity(envelope("e-5", payload(0, 0, "LIC123")));

        verify(drivers, never()).openSession(anyString(), any(), anyString(), anyString(), anyString(), anyString());
        verify(drivers, never()).closeOpenSession(anyString(), anyString());
    }

    @Test
    void ignoresNon0702Envelope() {
        MessageEnvelope other = new MessageEnvelope("e-6", "device-1", 0x0200L, 1, "2019",
                "2026-08-11T08:00:00Z", "signal-1", "location", Map.of());
        assertFalse(service.handleIfDriverIdentity(other));
        verify(drivers, never()).insertIdentityEvent(any());
    }
}
