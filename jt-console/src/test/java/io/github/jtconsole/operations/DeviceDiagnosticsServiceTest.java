package io.github.jtconsole.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.domain.TrackPoint;
import io.github.jtconsole.repository.AuditRepository;
import io.github.jtconsole.repository.MediaRepository;
import io.github.jtconsole.repository.TrackRepository;
import io.github.jtconsole.security.DataScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceDiagnosticsServiceTest {
    private ConnectionDiagnosticsService connections;
    private TrackRepository tracks;
    private MediaRepository media;
    private AuditRepository audits;
    private DeviceDiagnosticsService service;

    @BeforeEach
    void setUp() {
        connections = mock(ConnectionDiagnosticsService.class);
        tracks = mock(TrackRepository.class);
        media = mock(MediaRepository.class);
        audits = mock(AuditRepository.class);
        service = new DeviceDiagnosticsService(connections, tracks, media, audits);
        when(connections.query(anyString(), anyString(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(Map.of("summary", Map.of(), "timeline", List.of()));
        when(media.findByDeviceWindow(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of());
        when(audits.findDeviceActions(anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void clockUsesMedianAndRecognizesEightHourTimezoneError() {
        when(tracks.findRange(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        point("2026-08-21T08:00:00Z", "2026-08-21T00:00:00Z", 30, 120),
                        point("2026-08-21T08:05:00Z", "2026-08-21T00:00:00Z", 30, 120),
                        point("2026-08-21T08:30:00Z", "2026-08-21T00:00:00Z", 30, 120)));

        Map<String, Object> result = service.diagnose(
                "device-1", "2026-08-20", "2026-08-21", DataScope.tenantWide(1L));
        Map<String, Object> clock = dimension(result, "clock");

        assertThat(clock).containsEntry("sampleCount", 3)
                .containsEntry("medianOffsetMinutes", 485L);
        assertThat(clock.get("diagnosis").toString()).contains("疑似把北京时间当 UTC 上报");
    }

    @Test
    void positioningCountsZeroAndOutOfRangeCoordinates() {
        when(tracks.findRange(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        point("2026-08-21T10:00:00Z", "2026-08-21T10:00:01Z", 30, 120),
                        point("2026-08-21T10:01:00Z", "2026-08-21T10:01:01Z", 0, 0),
                        point("2026-08-21T10:02:00Z", "2026-08-21T10:02:01Z", 95, 120)));

        Map<String, Object> positioning = dimension(service.diagnose(
                "device-1", "2026-08-20", "2026-08-21", DataScope.tenantWide(1L)), "positioning");

        assertThat(positioning).containsEntry("sampleCount", 3)
                .containsEntry("positionedCount", 1L)
                .containsEntry("invalidCoordinateCount", 2L)
                .containsEntry("positionedFalseRatio", 2D / 3D)
                .containsEntry("lastValidPositionAt", "2026-08-21T10:00:00Z");
    }

    @Test
    void successfulPhotoCommandWithoutLaterPhotoIsReportedAsUploadFailure() {
        when(tracks.findRange(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of());
        when(audits.findDeviceActions(anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuditRepository.AuditEntryView(
                        "2026-08-21T10:00:00.000+08:00", "下发终端指令", "SUCCESS",
                        "/api/commands/photo", "指令=photo")))
                .thenReturn(List.of());
        when(media.findByDeviceWindow(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of(photo("2026-08-21T09:59:00.000+08:00")));

        Map<String, Object> photo = dimension(service.diagnose(
                "device-1", "2026-08-20", "2026-08-21", DataScope.tenantWide(1L)), "photoFollowUp");

        assertThat(photo).containsEntry("pendingCommandWithoutPhoto", true);
        assertThat(photo.get("diagnosis").toString())
                .contains("应答成功").contains("图片未到达平台");
    }

    @Test
    void oneUnavailableDimensionDoesNotHideOtherDimensions() {
        when(tracks.findRange(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenThrow(new IllegalStateException("clock unavailable"))
                .thenReturn(List.of(point(
                        "2026-08-21T10:00:00Z", "2026-08-21T10:00:01Z", 30, 120)));

        Map<String, Object> result = service.diagnose(
                "device-1", "2026-08-20", "2026-08-21", DataScope.tenantWide(1L));

        assertThat(dimension(result, "clock")).containsEntry("available", false);
        assertThat(dimension(result, "positioning"))
                .containsEntry("available", true).containsEntry("positionedCount", 1L);
        assertThat(result).containsKeys("connection", "photoFollowUp", "streamFollowUp");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dimension(Map<String, Object> result, String name) {
        return (Map<String, Object>) result.get(name);
    }

    private static TrackPoint point(
            String deviceTime, String receivedAt, double latitude, double longitude) {
        return new TrackPoint(deviceTime, receivedAt, latitude, longitude, latitude, longitude,
                null, null, null, null);
    }

    private static MediaFile photo(String capturedAt) {
        return new MediaFile(1L, "device-1", 1L, "图片", "jpg", "1.jpg", 100L,
                null, 1, 0, null, null, null, null, capturedAt);
    }
}
