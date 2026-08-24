package io.github.jtconsole.operations;

import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.domain.DeviceLogPage;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.repository.DeviceLogRepository;
import io.github.jtconsole.repository.DeviceLogRepository.DeviceLogFilter;
import io.github.jtconsole.security.DataScope;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 设备日志查询：页面与 AI 工具的唯一入口，租户隔离因此只有一处实现。
 *
 * <p><b>可见性判定必须在这里做</b>——日志库是独立文件，没有 {@code vehicle} 表可 join，
 * 仓储那一层只守得住 {@code tenant_id}。部门收窄与「这台设备归不归你」的判定靠
 * {@link VehicleService#requireVisibleDevice} 在业务库上完成，判定通过才去日志库取数。
 * 越权与不存在对外完全一致（{@code IamException 4004}），避免被用来探测他租户设备。
 */
@Service
public class DeviceLogQueryService {

    /** 三个方向之外的取值一律拒绝：静默忽略会让用户以为筛了，其实没筛。 */
    private static final Set<String> DIRECTIONS = Set.of("UP", "DOWN", "CONNECTION");
    private static final int MAX_PAGE_SIZE = 200;

    private final DeviceLogRepository logs;
    private final VehicleService vehicles;

    public DeviceLogQueryService(DeviceLogRepository logs, VehicleService vehicles) {
        this.logs = logs;
        this.vehicles = vehicles;
    }

    /**
     * 判定调用者能否看这台设备的日志。
     *
     * @return 规范化后的设备号
     * @throws io.github.jtconsole.iam.IamException 4004，设备不存在或不在范围内
     */
    public String authorize(String deviceId, DataScope scope) {
        String device = deviceId == null ? "" : deviceId.trim();
        if (device.isEmpty()) {
            throw new IllegalArgumentException("设备号不能为空");
        }
        // 平台管理员可查未建档设备的日志——终端连上了却建不了档，正是要查日志的场景。
        if (scope.isPlatform()) {
            return device;
        }
        return vehicles.requireVisibleDevice(device, scope);
    }

    public DeviceLogPage query(
            String deviceId, String start, String end, String direction, String msgId,
            String keyword, int page, int pageSize, DataScope scope) {
        String device = authorize(deviceId, scope);
        DeviceLogFilter filter = new DeviceLogFilter(
                device, start, end, normalizeDirection(direction), parseMessageId(msgId),
                keyword, requirePage(page), requirePageSize(pageSize));
        long total = logs.count(filter, scope);
        List<DeviceLog> items = total == 0 ? List.of() : logs.findByDevice(filter, scope);
        return new DeviceLogPage(items, total, filter.page(), filter.pageSize());
    }

    /**
     * 按主键取单条，用于展开原始 hex 与解析 JSON。
     *
     * <p>先取行再判定它所属设备的可见性——顺序反过来就得让调用方先知道设备号，
     * 而 AI 那边拿到的只有列表里的 id。查不到与看不到一律返回空，不区分。
     */
    public Optional<DeviceLog> findById(long id, DataScope scope) {
        return logs.findById(id, scope).filter(log -> {
            try {
                authorize(log.deviceId(), scope);
                return true;
            } catch (IamException notVisible) {
                return false;
            }
        });
    }

    /**
     * 消息 ID 两种写法都认：{@code 0x0200}（人和模型的自然写法）与 {@code 512}（库里的存法）。
     *
     * <p>只认一种不是「少个便利」而是「查不出来还不报错」：模型几乎总会写十六进制，
     * 而库里存的是十进制，两者对不上时结果为空——看起来就像这台设备没发过这种报文。
     */
    public static Integer parseMessageId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.regionMatches(true, 0, "0x", 0, 2)) {
                return Integer.parseInt(text.substring(2), 16);
            }
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "消息 ID 格式不正确：" + value + "，请写成 0x0200 或 512");
        }
    }

    private static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return null;
        }
        String normalized = direction.trim().toUpperCase(Locale.ROOT);
        if (!DIRECTIONS.contains(normalized)) {
            throw new IllegalArgumentException("方向只能是 UP、DOWN 或 CONNECTION，收到：" + direction);
        }
        return normalized;
    }

    private static int requirePage(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("页码从 1 起");
        }
        return page;
    }

    private static int requirePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("每页条数必须在 1..." + MAX_PAGE_SIZE + " 之间");
        }
        return pageSize;
    }
}
