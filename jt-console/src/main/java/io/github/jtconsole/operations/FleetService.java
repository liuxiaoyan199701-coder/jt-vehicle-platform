package io.github.jtconsole.operations;

import io.github.jtconsole.domain.Fleet;
import io.github.jtconsole.domain.FleetDetails;
import io.github.jtconsole.domain.FleetSummary;
import io.github.jtconsole.repository.FleetRepository;
import io.github.jtconsole.repository.VehicleRepository;
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
    public List<FleetSummary> findAll(String keyword) {
        return fleets.findAllSummaries(keyword == null ? "" : keyword.trim(), today());
    }

    @Transactional(readOnly = true)
    public FleetDetails find(long id) {
        Fleet fleet = requireFleet(id);
        FleetSummary summary = fleets.findSummary(id, today())
                .orElseThrow(() -> FleetBusinessException.notFound("车队不存在"));
        return new FleetDetails(fleet, summary, fleets.findMembers(id, today()));
    }

    @Transactional
    public FleetDetails create(FleetInput input) {
        Fleet normalized = normalize(0, input, null);
        if (fleets.codeExists(normalized.code(), null)) {
            throw FleetBusinessException.conflict("车队编码已存在：" + normalized.code());
        }
        long id;
        try {
            id = fleets.insert(normalized);
        } catch (DataIntegrityViolationException duplicate) {
            throw FleetBusinessException.conflict("车队编码已存在：" + normalized.code());
        }
        return find(id);
    }

    @Transactional
    public FleetDetails update(long id, FleetInput input) {
        Fleet current = requireFleet(id);
        Fleet normalized = normalize(id, input, current.createdAt());
        if (fleets.codeExists(normalized.code(), id)) {
            throw FleetBusinessException.conflict("车队编码已存在：" + normalized.code());
        }
        try {
            if (fleets.update(normalized) == 0) {
                throw FleetBusinessException.notFound("车队不存在");
            }
        } catch (DataIntegrityViolationException duplicate) {
            throw FleetBusinessException.conflict("车队编码已存在：" + normalized.code());
        }
        return find(id);
    }

    @Transactional
    public void delete(long id) {
        requireFleet(id);
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
    public FleetDetails replaceMembers(long id, List<String> rawDeviceIds) {
        requireFleet(id);
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

        // Complete validation before the first write so a mixed valid/invalid batch is atomic.
        for (String deviceId : requested) {
            if (!vehicles.exists(deviceId)) {
                throw FleetBusinessException.notFound("车辆不存在：" + deviceId);
            }
        }

        for (String current : fleets.memberIds(id)) {
            if (!requested.contains(current)) fleets.removeMember(id, current);
        }
        String assignedAt = Instant.now().toString();
        for (String deviceId : requested) fleets.assign(id, deviceId, assignedAt);
        return find(id);
    }

    private Fleet requireFleet(long id) {
        if (id <= 0) throw FleetBusinessException.notFound("车队不存在");
        return fleets.findById(id)
                .orElseThrow(() -> FleetBusinessException.notFound("车队不存在"));
    }

    private Fleet normalize(long id, FleetInput input, String createdAt) {
        if (input == null) throw FleetBusinessException.invalid("车队档案不能为空");
        String code = required(input.code(), "车队编码", 32);
        String name = required(input.name(), "车队名称", 100);
        String manager = optional(input.manager(), "负责人", 50);
        String contactPhone = optional(input.contactPhone(), "联系电话", 50);
        String remark = optional(input.remark(), "备注", 500);
        String now = Instant.now().toString();
        return new Fleet(id, code, name, manager, contactPhone, remark,
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
            String code, String name, String manager, String contactPhone, String remark) {}
}
