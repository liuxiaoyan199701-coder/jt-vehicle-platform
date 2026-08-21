package io.github.jtconsole.operations;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Driver;
import io.github.jtconsole.domain.DriverIdentityEvent;
import io.github.jtconsole.domain.DriverSession;
import io.github.jtconsole.repository.DriverRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriverService {

    private final DriverRepository drivers;

    public DriverService(DriverRepository drivers) {
        this.drivers = drivers;
    }

    public DriverPage search(String keyword, Long departmentId, DataScope scope, int page, int pageSize) {
        List<Driver> items = drivers.search(keyword, departmentId, scope, page, pageSize);
        long total = drivers.count(keyword, departmentId, scope);
        return new DriverPage(items, total);
    }

    public Optional<Driver> findById(long id, DataScope scope) {
        return drivers.findById(id, scope);
    }

    @Transactional
    public Driver create(AuthorizedPrincipal caller, Driver input) {
        Long tenantId = requireOwningTenant(caller, input);
        Driver value = validate(input);
        long id = drivers.insert(new Driver(null, value.name(), value.idCard(), value.licenseNo(),
                value.institution(), value.licenseValidPeriod(), value.phone(), value.remark(),
                value.departmentId(), tenantId, null, null));
        return drivers.findById(id, caller.scope()).orElseThrow();
    }

    @Transactional
    public Optional<Driver> update(long id, Driver input, DataScope scope) {
        if (drivers.findById(id, scope).isEmpty()) return Optional.empty();
        Driver value = validate(input);
        drivers.update(id, value);
        return drivers.findById(id, scope);
    }

    @Transactional
    public boolean delete(long id, DataScope scope) {
        if (drivers.findById(id, scope).isEmpty()) return false;
        return drivers.delete(id) == 1;
    }

    /** 当前驾驶员：该车未结束的驾驶区间 + 司机档案（若有）。 */
    public Optional<DriverSession> currentSession(String deviceId) {
        return drivers.findCurrentSession(deviceId);
    }

    /** 手动绑定：结束既有区间后开一段 MANUAL 区间。 */
    @Transactional
    public DriverSession manualBind(String deviceId, long driverId, DataScope scope) {
        Driver driver = drivers.findById(driverId, scope)
                .orElseThrow(() -> new IllegalArgumentException("司机不存在"));
        String now = Timestamps.now();
        drivers.closeOpenSession(deviceId, now);
        drivers.openSession(deviceId, driver.id(), driver.name(), driver.licenseNo(),
                now, DriverSession.SOURCE_MANUAL);
        return drivers.findCurrentSession(deviceId).orElseThrow();
    }

    /** 手动解绑：结束当前区间。 */
    @Transactional
    public void manualUnbind(String deviceId) {
        drivers.closeOpenSession(deviceId, Timestamps.now());
    }

    public List<DriverSession> sessions(long driverId, DataScope scope) {
        return drivers.findSessionsByDriver(driverId, scope, 50);
    }

    public List<DriverIdentityEvent> identityEvents(
            String deviceId, Boolean unmatched, Boolean failed, String start, String end,
            DataScope scope, int page, int pageSize) {
        return drivers.searchIdentityEvents(deviceId, unmatched, failed, start, end, scope, page, pageSize);
    }

    private static Long requireOwningTenant(AuthorizedPrincipal caller, Driver input) {
        if (!caller.platform()) {
            return caller.tenantId();
        }
        if (input == null || input.tenantId() == null) {
            throw new IllegalArgumentException("请先选择司机所属租户");
        }
        return input.tenantId();
    }

    private static Driver validate(Driver input) {
        if (input == null) throw new IllegalArgumentException("司机不能为空");
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isEmpty() || name.length() > 50) throw new IllegalArgumentException("司机姓名不合法");
        String idCard = input.idCard() == null ? "" : input.idCard().trim();
        if (idCard.isEmpty()) throw new IllegalArgumentException("身份证号不能为空");
        String licenseNo = input.licenseNo() == null ? "" : input.licenseNo().trim();
        if (licenseNo.isEmpty() || licenseNo.length() > 20) throw new IllegalArgumentException("从业资格证编码不合法");
        String remark = input.remark() == null ? "" : input.remark().trim();
        if (remark.length() > 500) throw new IllegalArgumentException("备注不能超过 500 字");
        return new Driver(input.id(), name, idCard, licenseNo,
                trim(input.institution()), trim(input.licenseValidPeriod()), trim(input.phone()),
                remark, input.departmentId(), input.tenantId(), input.createdAt(), input.updatedAt());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public record DriverPage(List<Driver> items, long total) {
    }
}
