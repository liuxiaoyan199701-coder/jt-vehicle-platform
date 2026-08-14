package io.github.jtconsole.operations;

import io.github.jtconsole.domain.Plan;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.iam.OrganizationService;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.repository.PlanRepository;
import io.github.jtconsole.repository.TenantRepository;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 车辆档案的租户与部门归属。
 *
 * <p>建档即确定设备归属，{@code deviceId} 是全局主键：被别的租户建过档一律冲突，
 * 且错误信息不透露归属方——否则冲突提示就变成了跨租户的设备归属查询接口。
 */
@Service
public class VehicleService {

    private final VehicleRepository vehicles;
    private final TenantRepository tenants;
    private final PlanRepository plans;
    private final OrganizationService organization;
    private final DeviceOwnershipCache ownership;

    public VehicleService(
            VehicleRepository vehicles,
            TenantRepository tenants,
            PlanRepository plans,
            OrganizationService organization,
            DeviceOwnershipCache ownership) {
        this.vehicles = vehicles;
        this.tenants = tenants;
        this.plans = plans;
        this.organization = organization;
        this.ownership = ownership;
    }

    @Transactional(readOnly = true)
    public List<Vehicle> list(DataScope scope) {
        return vehicles.findAll(scope);
    }

    @Transactional(readOnly = true)
    public Vehicle get(String deviceId, DataScope scope) {
        return vehicles.findById(canonicalDeviceId(deviceId), scope)
                .orElseThrow(() -> IamException.notFound("车辆不存在"));
    }

    @Transactional
    public Vehicle create(AuthorizedPrincipal caller, VehicleRequest request) {
        String deviceId = canonicalDeviceId(request.deviceId());
        String plateNo = requirePlate(request.plateNo());
        Long tenantId = resolveTargetTenant(caller, request.tenantId());
        if (tenantId == null) {
            throw IamException.invalid("请先选择车辆所属租户");
        }
        if (vehicles.exists(deviceId)) {
            // 刻意不区分「本租户已建档」与「他租户已建档」：区分即泄露归属。
            throw IamException.conflict("终端号已存在：" + deviceId);
        }
        organization.requireDepartmentInTenant(request.departmentId(), tenantId);
        enforceVehicleQuota(tenantId);

        Vehicle vehicle = new Vehicle(
                deviceId, plateNo, request.plateColor(), request.brand(),
                request.channels(), request.remark(),
                tenantId, request.departmentId(), null, null);
        vehicles.insert(vehicle);
        ownership.put(deviceId, tenantId, request.departmentId());
        return vehicles.findByIdUnscoped(deviceId).orElse(vehicle);
    }

    @Transactional
    public Vehicle update(AuthorizedPrincipal caller, String deviceId, VehicleRequest request) {
        String canonicalId = canonicalDeviceId(deviceId);
        Vehicle existing = vehicles.findById(canonicalId, caller.scope())
                .orElseThrow(() -> IamException.notFound("车辆不存在"));
        organization.requireDepartmentInTenant(request.departmentId(), existing.tenantId());

        Vehicle updated = new Vehicle(
                canonicalId, requirePlate(request.plateNo()), request.plateColor(),
                request.brand(), request.channels(),
                request.remark(), existing.tenantId(), request.departmentId(), null, null);
        if (vehicles.update(updated) == 0) {
            throw IamException.notFound("车辆不存在");
        }
        ownership.put(canonicalId, existing.tenantId(), request.departmentId());
        return vehicles.findByIdUnscoped(canonicalId).orElse(updated);
    }

    @Transactional
    public void delete(AuthorizedPrincipal caller, String deviceId) {
        String canonicalId = canonicalDeviceId(deviceId);
        vehicles.findById(canonicalId, caller.scope())
                .orElseThrow(() -> IamException.notFound("车辆不存在"));
        vehicles.delete(canonicalId);
        ownership.remove(canonicalId);
    }

    /**
     * 跨租户调拨。仅平台管理员可执行：调拨会让该设备的全部历史数据（轨迹、告警、统计、
     * 多媒体）随归属一起转移可见性，这不是租户自己能决定的事。
     */
    @Transactional
    public Vehicle reassign(AuthorizedPrincipal caller, String deviceId, long targetTenantId) {
        if (!caller.platform()) {
            throw IamException.notFound("车辆不存在");
        }
        String canonicalId = canonicalDeviceId(deviceId);
        Vehicle existing = vehicles.findByIdUnscoped(canonicalId)
                .orElseThrow(() -> IamException.notFound("车辆不存在"));
        if (existing.tenantId() != null && existing.tenantId() == targetTenantId) {
            return existing;
        }
        tenants.findById(targetTenantId)
                .orElseThrow(() -> IamException.notFound("目标租户不存在"));
        enforceVehicleQuota(targetTenantId);
        vehicles.reassignTenant(canonicalId, targetTenantId);
        ownership.put(canonicalId, targetTenantId, null);
        return vehicles.findByIdUnscoped(canonicalId).orElseThrow();
    }

    /** 校验目标设备落在调用者数据范围内。指令、开流、多媒体等入口在触达网关前调用。 */
    @Transactional(readOnly = true)
    public String requireVisibleDevice(String deviceId, DataScope scope) {
        String canonicalId = canonicalDeviceId(deviceId);
        if (!vehicles.visible(canonicalId, scope)) {
            throw IamException.notFound("车辆不存在");
        }
        return canonicalId;
    }

    private void enforceVehicleQuota(long tenantId) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> IamException.notFound("租户不存在"));
        if (tenant.planId() == null) {
            return;
        }
        Optional<Plan> plan = plans.findById(tenant.planId());
        if (plan.isEmpty()) {
            return;
        }
        int current = vehicles.countByTenant(tenantId);
        if (plan.get().vehicleQuotaExceeded(current)) {
            throw IamException.quotaExceeded(
                    "车辆数已达套餐上限 " + plan.get().maxVehicles() + "，请先升级套餐");
        }
    }

    private Long resolveTargetTenant(AuthorizedPrincipal caller, Long requested) {
        if (!caller.platform()) {
            return caller.tenantId();
        }
        if (requested == null) {
            return null;
        }
        return tenants.findById(requested)
                .map(Tenant::id)
                .orElseThrow(() -> IamException.notFound("租户不存在"));
    }

    /**
     * 终端号只做去空白处理。MUST NOT 去前导零或按数值归一：
     * {@code 00123} 与 {@code 123} 是两台不同的设备。
     */
    private static String canonicalDeviceId(String deviceId) {
        String trimmed = deviceId == null ? "" : deviceId.trim();
        if (trimmed.isEmpty()) {
            throw IamException.invalid("终端号不能为空");
        }
        return trimmed;
    }

    private static String requirePlate(String plateNo) {
        String trimmed = plateNo == null ? "" : plateNo.trim();
        if (trimmed.isEmpty()) {
            throw IamException.invalid("车牌号不能为空");
        }
        return trimmed;
    }

    /**
     * 车辆写入请求。可选字段一律用包装类型：Jackson 对缺省的 JSON 字段给的是 null，
     * 映射到基本类型会直接抛 HttpMessageNotReadableException，
     * 把「没填」变成「请求格式错误」。租户由服务端按调用者身份决定，平台管理员才可显式指定。
     */
    public record VehicleRequest(
            String deviceId,
            String plateNo,
            String plateColor,
            String brand,
            Integer channelCount,
            String remark,
            Long tenantId,
            Long departmentId) {

        int channels() {
            return channelCount == null || channelCount <= 0 ? 1 : channelCount;
        }
    }
}
