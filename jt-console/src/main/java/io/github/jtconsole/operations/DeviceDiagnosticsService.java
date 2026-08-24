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
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 一次设备体检的五个相互独立的数据维度。单维查询失败不会遮蔽其它维度。 */
@Service
public class DeviceDiagnosticsService {
    private static final String KIND_COMMAND_RESULT = "COMMAND_RESULT";
    private static final String KIND_STREAM_NOT_ARRIVED = "STREAM_NOT_ARRIVED";
    private static final String PHOTO_COMMAND = "0x8801";
    private static final String STREAM_COMMAND = "0x9101";
    private static final String OUTCOME_OK = "OK";
    private static final String OUTCOME_REJECTED = "REJECTED";
    private static final String OUTCOME_TIMEOUT = "TIMEOUT";
    private static final String OUTCOME_OFFLINE = "OFFLINE";
    private static final String OUTCOME_FAILED = "FAILED";

    private final ConnectionDiagnosticsService connections;
    private final TrackRepository tracks;
    private final MediaRepository media;
    private final AuditRepository audits;
    private final ObjectMapper json = new ObjectMapper();

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
        putDimension(result, "streamFollowUp", () -> streamFollowUp(deviceId, start, end, scope));
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

    /**
     * 拍照跟拍：优先用网关的 0x8801 指令结局事件断言，能区分「终端拒绝」「应答超时」
     * 「已应答但图片未到」；没有事件数据时（升级前的历史时段）退回审计推断。
     */
    private Map<String, Object> photoFollowUp(
            String deviceId, String start, String end, DataScope scope) {
        List<MediaFile> photos = media.findByDeviceWindow(deviceId, start, end, 100, scope);
        List<LinkEvent> commands = commandResults(deviceId, start, end, scope, PHOTO_COMMAND);
        if (commands.isEmpty()) {
            return photoFollowUpFromAudit(deviceId, start, end, scope, photos);
        }
        LinkEvent latest = latest(commands);
        String outcome = latest.text("outcome");
        boolean photoArrived = photos.stream()
                .anyMatch(photo -> atOrAfter(photo.capturedAt(), latest.eventTime()));
        boolean pending = OUTCOME_OK.equals(outcome) && !photoArrived;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("commandCount", commands.size());
        result.put("photoCount", photos.size());
        result.put("lastCommandAt", latest.eventTime());
        result.put("pendingCommandWithoutPhoto", pending);
        result.put("diagnosis", photoDiagnosis(outcome, latest.integer("resultCode"), pending));
        result.put("note", "基于网关指令应答事件");
        return result;
    }

    private static String photoDiagnosis(String outcome, Integer resultCode, boolean pending) {
        return switch (outcome == null ? "" : outcome) {
            case OUTCOME_REJECTED -> "终端拒绝拍照指令" + resultCodeSuffix(resultCode);
            case OUTCOME_TIMEOUT -> "拍照指令已下发，终端未应答（超时）";
            case OUTCOME_OFFLINE -> "拍照指令未送达终端：设备离线";
            case OUTCOME_FAILED -> "拍照指令未送达终端：下发失败";
            default -> pending
                    ? "指令已下发且应答成功，但图片未到达平台（多媒体上传链路可能故障）"
                    : "未发现拍照指令有令无图";
        };
    }

    /** 一波的推断口径：审计只知道平台发出了指令，不知道终端答没答，故只在无事件时使用。 */
    private Map<String, Object> photoFollowUpFromAudit(
            String deviceId, String start, String end, DataScope scope, List<MediaFile> photos) {
        List<AuditRepository.AuditEntryView> commands = audits.findDeviceActions(
                        deviceId, List.of("下发终端指令", "下发拍照指令"), start, end, scope)
                .stream().filter(DeviceDiagnosticsService::successfulPhotoCommand).toList();
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
        result.put("note", "基于审计推断：该时段没有采集到指令应答事件");
        return result;
    }

    /**
     * 开流跟流：关联 0x9101 的指令结局与媒体侧的无流到达，给出确定结论，
     * 而不是一波那句「媒体到达情况需查媒体节点」。
     */
    private Map<String, Object> streamFollowUp(
            String deviceId, String start, String end, DataScope scope) {
        List<LinkEvent> events = linkEvents(deviceId, start, end, scope);
        List<LinkEvent> commands = filterCommand(events, STREAM_COMMAND);
        List<LinkEvent> notArrived = events.stream()
                .filter(event -> KIND_STREAM_NOT_ARRIVED.equals(event.kind())).toList();
        if (commands.isEmpty() && notArrived.isEmpty()) {
            return streamFollowUpFromAudit(deviceId, scope);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("commandCount", commands.size());
        result.put("notArrivedCount", notArrived.size());
        result.put("lastCommandAt", commands.isEmpty() ? null : latest(commands).eventTime());
        result.put("diagnosis", streamDiagnosis(commands, notArrived));
        result.put("note", "基于网关指令应答与媒体节点无流到达事件");
        return result;
    }

    private static String streamDiagnosis(List<LinkEvent> commands, List<LinkEvent> notArrived) {
        LinkEvent latestNotArrived = notArrived.isEmpty() ? null : latest(notArrived);
        if (commands.isEmpty()) {
            return "已开流但码流未到达媒体节点" + mediaNodeSuffix(latestNotArrived);
        }
        LinkEvent latest = latest(commands);
        return switch (latest.text("outcome") == null ? "" : latest.text("outcome")) {
            case OUTCOME_REJECTED -> "终端拒绝开流指令" + resultCodeSuffix(latest.integer("resultCode"));
            case OUTCOME_TIMEOUT -> "开流指令已下发，终端未应答（超时）";
            case OUTCOME_OFFLINE -> "开流指令未送达终端：设备离线";
            case OUTCOME_FAILED -> "开流指令未送达终端：下发失败";
            default -> latestNotArrived != null
                    && atOrAfter(latestNotArrived.eventTime(), latest.eventTime())
                    ? "终端已应答开流，但码流未到达媒体节点" + mediaNodeSuffix(latestNotArrived)
                    : "开流指令已应答且未出现无流到达事件，码流已到达媒体节点";
        };
    }

    /** 一波的推断口径：审计只能证明平台下发过开流指令。 */
    private Map<String, Object> streamFollowUpFromAudit(String deviceId, DataScope scope) {
        List<AuditRepository.AuditEntryView> commands = audits.findDeviceActions(
                deviceId, List.of("开启实时视频", "回放录像"), null, null, scope);
        return Map.of("available", true, "commandCount", commands.size(),
                "note", commands.isEmpty()
                        ? "没有审计到开流指令"
                        : "基于审计推断：该时段没有采集到指令应答与无流到达事件");
    }

    private static String resultCodeSuffix(Integer resultCode) {
        if (resultCode == null) {
            return "";
        }
        String meaning = switch (resultCode) {
            case 1 -> "执行失败";
            case 2 -> "消息有误";
            case 3 -> "不支持该指令";
            case 4 -> "报警处理确认";
            default -> null;
        };
        return meaning == null
                ? "（结果码 " + resultCode + "）"
                : "（结果码 " + resultCode + "：" + meaning + "）";
    }

    private static String mediaNodeSuffix(LinkEvent notArrived) {
        String node = notArrived == null ? null : notArrived.text("mediaInstanceId");
        return node == null ? "" : "（节点 " + node + "）";
    }

    private List<LinkEvent> commandResults(
            String deviceId, String start, String end, DataScope scope, String commandMsgId) {
        return filterCommand(linkEvents(deviceId, start, end, scope), commandMsgId);
    }

    private static List<LinkEvent> filterCommand(List<LinkEvent> events, String commandMsgId) {
        return events.stream()
                .filter(event -> KIND_COMMAND_RESULT.equals(event.kind()))
                .filter(event -> commandMsgId.equalsIgnoreCase(event.text("commandMsgId")))
                .toList();
    }

    private List<LinkEvent> linkEvents(String deviceId, String start, String end, DataScope scope) {
        return connections.events(deviceId, start, end, scope).stream()
                .map(event -> new LinkEvent(event.kind(), event.eventTime(), detail(event.detail())))
                .toList();
    }

    /** 不依赖仓储的排序：结论取的是「最近一次」，顺序错了结论就错了。 */
    private static LinkEvent latest(List<LinkEvent> events) {
        return events.stream()
                .max(Comparator.comparing(LinkEvent::eventTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();
    }

    private Map<String, Object> detail(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(stored, new TypeReference<Map<String, Object>>() { });
        } catch (JacksonException unreadable) {
            return Map.of();
        }
    }

    private record LinkEvent(String kind, String eventTime, Map<String, Object> detail) {
        String text(String key) {
            Object value = detail.get(key);
            return value == null || value.toString().isBlank() ? null : value.toString().trim();
        }

        Integer integer(String key) {
            return detail.get(key) instanceof Number number ? number.intValue() : null;
        }
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
