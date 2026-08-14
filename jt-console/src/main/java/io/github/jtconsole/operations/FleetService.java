package io.github.jtconsole.operations;

import io.github.jtconsole.domain.Fleet;
import io.github.jtconsole.domain.FleetDetails;
import io.github.jtconsole.domain.FleetSummary;
import io.github.jtconsole.repository.FleetRepository;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FleetService {

    private final FleetRepository fleets;
    private final VehicleRepository vehicles;
    private final BusinessDateService businessDates;

    public FleetService(
            FleetRepository fleets, VehicleRepository vehicles, BusinessDateService businessDates) {
        this.fleets = fleets;
        this.vehicles = vehicles;
        this.businessDates = businessDates;
    }

    @Transactional(readOnly = true)
    public List<FleetSummary> findAll(String keyword, DataScope scope) {
        return fleets.findAllSummaries(keyword == null ? "" : keyword.trim(), today(), scope);
    }

    @Transactional(readOnly = true)
    public FleetDetails find(long id, DataScope scope) {
        Fleet fleet = requireFleet(id, scope);
        FleetSummary summary = fleets.findSummary(id, today(), scope)
                .orElseThrow(() -> FleetBusinessException.notFound("车队不存在"));
        return new FleetDetails(fleet, summary, fleets.findMembers(id, today(), scope));
    }

    @Transactional
    public FleetDetails create(AuthorizedPrincipal caller, FleetInput input) {
        Long tenantId = requireOwningTenant(caller, input);
        Fleet normalized = normalize(0, input, null, tenantId);
        if (fleets.codeExists(normalized.code(), null, tenantId)) {
            throw FleetBusinessException.conflict("车队编码已存在：" + normalized.code());
        }
        long id;
        try {
            id = fleets.insert(normalized);
        } catch (DataIntegrityViolationException duplicate) {
            throw FleetBusinessException.conflict("车队编码已存在：" + normalized.code());
        }
        return find(id, caller.scope());
    }

    @Transactional
    public FleetDetails update(AuthorizedPrincipal caller, long id, FleetInput input) {
        Fleet current = requireFleet(id, caller.scope());
        Fleet normalized = normalize(id, input, current.createdAt(), current.tenantId());
        if (fleets.codeExists(normalized.code(), id, current.tenantId())) {
            throw FleetBusinessException.conflict("车队编码已存在：" + normalized.code());
        }
        try {
            if (fleets.update(normalized) == 0) {
                throw FleetBusinessException.notFound("车队不存在");
            }
        } catch (DataIntegrityViolationException duplicate) {
            throw FleetBusinessException.conflict("车队编码已存在：" + normalized.code());
        }
        return find(id, caller.scope());
    }

    @Transactional
    public void delete(long id, DataScope scope) {
        requireFleet(id, scope);
        int members = fleets.memberCount(id);
        if (members > 0) {
            throw FleetBusinessException.conflict("车队仍有 " + members + " 辆车辆，请先移出或调拨");
        }
        if (fleets.deleteIfEmpty(id) == 0) {
            if (fleets.memberCount(id) > 0) {
                throw FleetBusinessException.conflict("车队仍有车辆，请先移出或调拨");
            }
            throw FleetBusinessException.notFound("车队不存在");
        }
    }

    @Transactional
    public FleetDetails replaceMembers(long id, List<String> rawDeviceIds, DataScope scope) {
        requireFleet(id, scope);
        if (rawDeviceIds == null) {
            throw FleetBusinessException.invalid("车辆列表不能为空");
        }

        Set<String> requested = new LinkedHashSet<>();
        for (String raw : rawDeviceIds) {
            if (raw == null || raw.trim().isEmpty()) {
                throw FleetBusinessException.invalid("终端号不能为空");
            }
            requested.add(raw.trim());
        }

        // 写入前完成全部校验，混杂了非法项的批次因此保持原子性。
        // 用范围内可见性而不是全局存在性判定：范围外的车辆对调用者就该是「不存在」。
        for (String deviceId : requested) {
            if (!vehicles.visible(deviceId, scope)) {
                throw FleetBusinessException.notFound("车辆不存在：" + deviceId);
            }
        }

        for (String current : fleets.memberIds(id)) {
            if (!requested.contains(current)) fleets.removeMember(id, current);
        }
        String assignedAt = Instant.now().toString();
        for (String deviceId : requested) fleets.assign(id, deviceId, assignedAt);
        return find(id, scope);
    }

    private Fleet requireFleet(long id, DataScope scope) {
        if (id <= 0) throw FleetBusinessException.notFound("车队不存在");
        return fleets.findById(id, scope)
                .orElseThrow(() -> FleetBusinessException.notFound("车队不存在"));
    }

    /** 车队归属租户：租户用户即自己的租户，平台管理员必须显式指定。 */
    private static Long requireOwningTenant(AuthorizedPrincipal caller, FleetInput input) {
        if (!caller.platform()) {
            return caller.tenantId();
        }
        if (input == null || input.tenantId() == null) {
            throw FleetBusinessException.invalid("请先选择车队所属租户");
        }
        return input.tenantId();
    }

    private Fleet normalize(long id, FleetInput input, String createdAt, Long tenantId) {
        if (input == null) throw FleetBusinessException.invalid("车队档案不能为空");
        String code = required(input.code(), "车队编码", 32);
        String name = required(input.name(), "车队名称", 100);
        String manager = optional(input.manager(), "负责人", 50);
        String contactPhone = optional(input.contactPhone(), "联系电话", 50);
        String remark = optional(input.remark(), "备注", 500);
        String now = Instant.now().toString();
        return new Fleet(id, code, name, manager, contactPhone, remark, tenantId,
                createdAt == null ? now : createdAt, now);
    }

    private static String required(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw FleetBusinessException.invalid(label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw FleetBusinessException.invalid(label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private static String optional(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw FleetBusinessException.invalid(label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String today() {
        return businessDates.today().toString();
    }

    public record FleetInput(
            String code, String name, String manager, String contactPhone, String remark,
            Long tenantId) {}
}
