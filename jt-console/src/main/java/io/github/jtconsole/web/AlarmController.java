package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.AlarmEvent;
import io.github.jtconsole.domain.AlarmLevel;
import io.github.jtconsole.domain.AlarmPage;
import io.github.jtconsole.domain.AlarmSource;
import io.github.jtconsole.domain.AlarmStatus;
import io.github.jtconsole.operations.AlarmService;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmRepository alarms;
    private final AlarmService service;

    public AlarmController(AlarmRepository alarms, AlarmService service) {
        this.alarms = alarms;
        this.service = service;
    }

    @GetMapping
    @RequirePermission(Permissions.ALARM_LIST)
    public ApiResponse<AlarmPage> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            DataScope scope) {
        if (page < 1 || pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("分页参数不合法");
        }
        AlarmRepository.AlarmFilter filter = new AlarmRepository.AlarmFilter(
                parse(AlarmStatus.class, status), parse(AlarmLevel.class, level),
                parse(AlarmSource.class, source), trim(deviceId), trim(type), trim(keyword),
                trim(start), trim(end), page, pageSize);
        return ApiResponse.ok(alarms.search(filter, scope));
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.ALARM_LIST)
    public ApiResponse<AlarmEvent> get(@PathVariable long id, DataScope scope) {
        return alarms.findById(id, scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "告警不存在"));
    }

    @PostMapping("/{id}/acknowledge")
    @RequirePermission(Permissions.ALARM_HANDLE)
    @Audited(value = "确认告警", resourceType = "alarm")
    public ApiResponse<AlarmEvent> acknowledge(
            @PathVariable long id, @RequestBody ActionRequest body,
            Principal principal, DataScope scope) {
        return action(id, service.acknowledge(
                id, body == null ? null : body.note(), operator(principal), scope), scope);
    }

    @PostMapping("/{id}/close")
    @RequirePermission(Permissions.ALARM_HANDLE)
    @Audited(value = "关闭告警", resourceType = "alarm")
    public ApiResponse<AlarmEvent> close(
            @PathVariable long id, @RequestBody ActionRequest body,
            Principal principal, DataScope scope) {
        return action(id, service.close(
                id, body == null ? null : body.note(), operator(principal), scope), scope);
    }

    private ApiResponse<AlarmEvent> action(
            long id, AlarmService.ActionResult result, DataScope scope) {
        return switch (result) {
            case UPDATED -> ApiResponse.ok(alarms.findById(id, scope).orElseThrow());
            case NOT_FOUND -> ApiResponse.error("4004", "告警不存在");
            case INVALID_STATE -> ApiResponse.error("4009", "告警状态不允许该操作");
        };
    }

    private static String operator(Principal principal) {
        return principal == null || principal.getName() == null ? "admin" : principal.getName();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("筛选枚举值不合法");
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ActionRequest(String note) {}
}
