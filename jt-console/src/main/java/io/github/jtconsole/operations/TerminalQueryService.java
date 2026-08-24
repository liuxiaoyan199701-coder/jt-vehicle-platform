package io.github.jtconsole.operations;

import io.github.jtconsole.domain.TerminalPage;
import io.github.jtconsole.repository.TerminalQueryRepository;
import io.github.jtconsole.repository.TerminalQueryRepository.TerminalFilter;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.repository.TerminalRepository;
import io.github.jtconsole.domain.Terminal;
import io.github.jtconsole.iam.IamException;
import org.springframework.stereotype.Service;

/** 终端清单查询与一键建档。建档不另开写路径，原样交给 {@link VehicleService}。 */
@Service
public class TerminalQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final TerminalQueryRepository query;
    private final TerminalRepository terminals;
    private final VehicleService vehicles;

    public TerminalQueryService(
            TerminalQueryRepository query, TerminalRepository terminals, VehicleService vehicles) {
        this.query = query;
        this.terminals = terminals;
        this.vehicles = vehicles;
    }

    public TerminalPage search(
            String keyword, Boolean archived, Boolean online, String start, String end,
            int page, int pageSize, DataScope scope) {
        return query.search(
                new TerminalFilter(keyword, archived, online, start, end,
                        requirePage(page), requirePageSize(pageSize)),
                scope);
    }

    /**
     * 把台账里的一台终端建成车辆档案。
     *
     * <p>校验一条都不绕过：终端号唯一性、车牌必填、租户与部门归属、套餐配额全部由
     * {@link VehicleService#create} 原路走一遍——从这里进和从车辆档案页进，是同一个动作。
     *
     * <p>请求里没给车牌时用终端自报的兜底，但**自报值只是兜底不是默认**：
     * 前端在打开表单时就把它预填出来让人确认过了，这里再兜一次是为了直连接口的调用方。
     */
    public Vehicle archive(
            AuthorizedPrincipal caller, String deviceId, VehicleService.VehicleRequest request) {
        Terminal terminal = terminals.findById(deviceId == null ? "" : deviceId.trim())
                .orElseThrow(() -> IamException.notFound("终端不存在"));
        String plateNo = request != null && request.plateNo() != null && !request.plateNo().isBlank()
                ? request.plateNo()
                : terminal.reportedPlate();
        return vehicles.create(caller, new VehicleService.VehicleRequest(
                terminal.deviceId(),
                plateNo,
                request == null ? null : request.plateColor(),
                request == null ? null : request.brand(),
                request == null ? null : request.channelCount(),
                request == null ? null : request.remark(),
                request == null ? null : request.tenantId(),
                request == null ? null : request.departmentId()));
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
