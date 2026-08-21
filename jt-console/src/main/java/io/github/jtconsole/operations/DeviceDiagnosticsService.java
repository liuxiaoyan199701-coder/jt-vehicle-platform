package io.github.jtconsole.operations;

import io.github.jtconsole.domain.ConnectionEvent;
import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.domain.TrackPoint;
import io.github.jtconsole.repository.AuditRepository;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.repository.MediaRepository;
import io.github.jtconsole.repository.TrackRepository;
import io.github.jtconsole.security.DataScope;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 一次设备体检的五个相互独立的数据维度。单维查询失败不会遮蔽其它维度。 */
@Service
public class DeviceDiagnosticsService {
    private final ConnectionDiagnosticsService connections;
    private final TrackRepository tracks;
    private final MediaRepository media;
    private final AuditRepository audits;

    public DeviceDiagnosticsService(
            ConnectionDiagnosticsService connections,
            TrackRepository tracks,
            MediaRepository media,
            AuditRepository audits) {
        this.connections = connections;
        this.tracks = tracks;
        this.media = media;
        this.audits = audits;
    }

    /** 体检前的唯一强制范围检查；越权不得被某个维度的独立降级吞掉。 */
    public void authorize(String deviceId, String start, String end, DataScope scope) {
        connections.query(deviceId, start, end, 1, 1, scope);
    }

    public Map<String, Object> diagnose(
            String deviceId, String start, String end, DataScope scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId == null ? "" : deviceId.trim());
        putDimension(result, "connection", () -> connections.query(deviceId, start, end, 1, 100, scope));
        putDimension(result, "clock", () -> clock(deviceId, start, end, scope));
        putDimension(result, "positioning", () -> positioning(deviceId, start, end, scope));
        putDimension(result, "photoFollowUp", () -> photoFollowUp(deviceId, start, end, scope));
        putDimension(result, "streamFollowUp", () -> streamFollowUp(deviceId, scope));
        return result;
    }

    private static void putDimension(Map<String, Object> result, String name, Dimension dimension) {
        try {
            result.put(name, dimension.read());
        } catch (RuntimeException failure) {
            result.put(name, Map.of("available", false, "note", "该维度数据暂不可用"));
        }
    }

    private Map<String, Object> clock(String deviceId, String start, String end, DataScope scope) {
        List<TrackPoint> points = tracks.findRange(deviceId, start, end, 200, scope);
        List<Long> offsets = points.stream().map(DeviceDiagnosticsService::offsetMinutes)
                .filter(value -> value != null).sorted().toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", !offsets.isEmpty());
        result.put("sampleCount", offsets.size());
        if (offsets.isEmpty()) {
            result.put("note", "没有同时可解析设备时间和接收时间的轨迹点");
            return result;
        }
        long median = offsets.get(offsets.size() / 2);
        result.put("medianOffsetMinutes", median);
        result.put("medianOffsetHours", Math.round(median / 60.0 * 10.0) / 10.0);
        if (Math.abs(Math.abs(median) - 8 * 60) <= 30) {
            result.put("diagnosis", "设备时间偏差约 8 小时，疑似把北京时间当 UTC 上报");
        } else if (Math.abs(median) > 10) {
            result.put("diagnosis", "设备时钟偏差约 " + Math.abs(median) + " 分钟");
        } else {
            result.put("diagnosis", "设备时钟基本正常");
        }
        return result;
    }

    private Map<String, Object> positioning(
            String deviceId, String start, String end, DataScope scope) {
        List<TrackPoint> points = tracks.findRange(deviceId, start, end, 500, scope);
        long invalid = points.stream().filter(point -> !valid(point)).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("sampleCount", points.size());
        result.put("positionedCount", points.size() - invalid);
        result.put("invalidCoordinateCount", invalid);
        result.put("positionedFalseRatio", points.isEmpty() ? 0D : invalid * 1D / points.size());
        result.put("lastValidPositionAt", points.stream().filter(DeviceDiagnosticsService::valid)
                .map(TrackPoint::deviceTime).max(Comparator.naturalOrder()).orElse(null));
        result.put("note", "历史未定位上报未落入轨迹表，未定位比例按已落库轨迹点统计");
        return result;
    }

    private Map<String, Object> photoFollowUp(
            String deviceId, String start, String end, DataScope scope) {
        List<AuditRepository.AuditEntryView> commands = audits.findDeviceActions(
                        deviceId, List.of("下发终端指令", "下发拍照指令"), start, end, scope)
                .stream().filter(DeviceDiagnosticsService::successfulPhotoCommand).toList();
        List<MediaFile> photos = media.findByDeviceWindow(deviceId, start, end, 100, scope);
        AuditRepository.AuditEntryView latestCommand = commands.stream()
                .max(Comparator.comparing(AuditRepository.AuditEntryView::occurredAt)).orElse(null);
        boolean photoArrivedAfterCommand = latestCommand != null && photos.stream()
                .anyMatch(photo -> atOrAfter(photo.capturedAt(), latestCommand.occurredAt()));
        boolean pending = latestCommand != null && !photoArrivedAfterCommand;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("commandCount", commands.size());
        result.put("photoCount", photos.size());
        result.put("lastCommandAt", latestCommand == null ? null : latestCommand.occurredAt());
        result.put("pendingCommandWithoutPhoto", pending);
        result.put("diagnosis", pending
                ? "指令已下发且应答成功，但图片未到达平台（多媒体上传链路可能故障）"
                : "未发现拍照指令有令无图");
        return result;
    }

    private Map<String, Object> streamFollowUp(String deviceId, DataScope scope) {
        List<AuditRepository.AuditEntryView> commands = audits.findDeviceActions(
                deviceId, List.of("开启实时视频", "回放录像"), null, null, scope);
        return Map.of("available", true, "commandCount", commands.size(),
                "note", commands.isEmpty()
                        ? "没有审计到开流指令"
                        : "平台已下发开流指令，媒体到达情况需查媒体节点");
    }

    private static boolean successfulPhotoCommand(AuditRepository.AuditEntryView command) {
        if (!"SUCCESS".equals(command.result())) {
            return false;
        }
        if ("下发拍照指令".equals(command.action())) {
            return true;
        }
        String detail = command.detail();
        String path = command.path();
        return detail != null && detail.contains("指令=photo")
                || path != null && path.endsWith("/photo");
    }

    private static boolean atOrAfter(String candidate, String boundary) {
        try {
            return !parse(candidate).isBefore(parse(boundary));
        } catch (RuntimeException invalidTime) {
            return false;
        }
    }

    private static boolean valid(TrackPoint point) {
        return Double.isFinite(point.lat()) && Double.isFinite(point.lng())
                && Math.abs(point.lat()) <= 90 && Math.abs(point.lng()) <= 180
                && !(point.lat() == 0 && point.lng() == 0);
    }

    static Long offsetMinutes(TrackPoint point) {
        try {
            Instant device = parse(point.deviceTime());
            Instant received = parse(point.receivedAt());
            return Duration.between(received, device).toMinutes();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static Instant parse(String value) {
        String normalized = value.trim().replace(' ', 'T');
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.parse(normalized + "+08:00").toInstant();
        }
    }

    @FunctionalInterface
    private interface Dimension { Object read(); }
}
