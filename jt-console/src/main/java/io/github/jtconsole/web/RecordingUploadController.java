package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.AuditContext;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.RecordingUploadTask;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.RecordingUploadRepository;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/recording-uploads")
public class RecordingUploadController {
    private static final Duration MAX_RANGE = Duration.ofDays(7);
    private final RecordingUploadRepository tasks;
    private final VehicleService vehicles;
    private final DeviceOwnershipCache ownership;
    private final RestClient commandGateway;

    public RecordingUploadController(
            RecordingUploadRepository tasks,
            VehicleService vehicles,
            DeviceOwnershipCache ownership,
            @Qualifier("commandGatewayRestClient") RestClient commandGateway) {
        this.tasks = tasks;
        this.vehicles = vehicles;
        this.ownership = ownership;
        this.commandGateway = commandGateway;
    }

    @PostMapping
    @RequirePermission(Permissions.RECORDING_PLAYBACK)
    @Audited(value = "下发录像上传指令", resourceType = "recording-upload")
    public ApiResponse<RecordingUploadTask> create(
            @RequestBody CreateRequest request, DataScope scope) {
        String requestedDevice = request.deviceId() == null ? "" : request.deviceId().trim();
        AuditContext.resource("vehicle", requestedDevice);
        // 越权校验优先于参数细节与任何网关调用，范围外设备统一表现为不存在。
        String deviceId = vehicles.requireVisibleDevice(requestedDevice, scope);
        request.validate();
        String taskId = UUID.randomUUID().toString();
        String now = Timestamps.now();
        Long tenantId = ownership.find(deviceId).map(DeviceOwnershipCache.Ownership::tenantId).orElse(null);
        RecordingUploadTask task = new RecordingUploadTask(
                taskId, tenantId, deviceId, null, request.channel(),
                Timestamps.of(request.startTime()), Timestamps.of(request.endTime()),
                request.mediaType(), request.streamType(), request.storageType(), request.condition(),
                "CREATED", null, null, null, null, null, null,
                now, now, null);
        tasks.insert(task);
        AuditContext.resource("recording-upload", taskId);
        AuditContext.detail("设备=" + deviceId + "，范围=" + task.startAt() + " 至 " + task.endAt());
        try {
            GatewayResponse response = commandGateway.post()
                    .uri("/device/9206")
                    .body(Map.ofEntries(
                            Map.entry("taskId", taskId),
                            Map.entry("deviceId", deviceId),
                            Map.entry("channel", request.channel()),
                            Map.entry("startTime", localTime(request.startTime())),
                            Map.entry("endTime", localTime(request.endTime())),
                            Map.entry("warnBit1", request.warnBit1()),
                            Map.entry("warnBit2", request.warnBit2()),
                            Map.entry("mediaType", request.mediaType()),
                            Map.entry("streamType", request.streamType()),
                            Map.entry("storageType", request.storageType()),
                            Map.entry("condition", request.condition())))
                    .retrieve().body(GatewayResponse.class);
            if (response == null || !response.accepted()) {
                tasks.markDispatchFailed(taskId, Timestamps.now());
                AuditContext.businessCode("5030");
                return ApiResponse.error("5030", "终端拒绝录像上传指令");
            }
            tasks.markDispatched(taskId, response.commandSerialNo(),
                    Timestamps.of(response.credentialExpiresAt()), Timestamps.now());
            return ApiResponse.ok(tasks.findById(taskId, scope).orElseThrow());
        } catch (RestClientException failure) {
            tasks.markDispatchFailed(taskId, Timestamps.now());
            AuditContext.businessCode("5030");
            return ApiResponse.error("5030", "录像上传指令下发失败：" + failure.getMessage());
        }
    }

    @GetMapping
    @RequirePermission(Permissions.RECORDING_SEARCH)
    public ApiResponse<List<RecordingUploadTask>> list(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "50") int limit,
            DataScope scope) {
        String canonicalId = vehicles.requireVisibleDevice(deviceId, scope);
        return ApiResponse.ok(tasks.findByDevice(canonicalId, Math.min(Math.max(limit, 1), 100), scope));
    }

    private static String localTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, Timestamps.ZONE).withNano(0).toString();
    }

    public record CreateRequest(
            String deviceId, int channel, Instant startTime, Instant endTime,
            long warnBit1, long warnBit2, int mediaType, int streamType,
            int storageType, int condition) {
        void validate() {
            if (channel < 1 || channel > 255) throw new IllegalArgumentException("通道号必须在 1 到 255 之间");
            if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
                throw new IllegalArgumentException("结束时间必须晚于开始时间");
            }
            if (Duration.between(startTime, endTime).compareTo(MAX_RANGE) > 0) {
                throw new IllegalArgumentException("录像上传时间跨度不能超过 7 天");
            }
            if (warnBit1 < 0 || warnBit1 > 0xffff_ffffL || warnBit2 < 0 || warnBit2 > 0xffff_ffffL) {
                throw new IllegalArgumentException("报警位必须是 32 位无符号整数");
            }
            if (mediaType < 0 || mediaType > 3 || streamType < 0 || streamType > 2
                    || storageType < 0 || storageType > 2 || condition < 0 || condition > 7) {
                throw new IllegalArgumentException("录像上传资源条件不合法");
            }
        }
    }

    private record GatewayResponse(
            String taskId, int commandSerialNo, boolean accepted, Instant credentialExpiresAt) { }
}
