package io.github.jtconsole.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.domain.Terminal;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.repository.TerminalQueryRepository;
import io.github.jtconsole.repository.TerminalRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.support.TestPrincipals;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 一键建档不另开写路径：配额、车牌、租户与部门校验全部由 {@link VehicleService#create}
 * 原路走一遍——从终端管理进和从车辆档案页进，必须是同一个动作。
 */
class TerminalArchiveTest {

    private static final String DEVICE = "138000000000";

    private final TerminalRepository terminals = mock(TerminalRepository.class);
    private final VehicleService vehicles = mock(VehicleService.class);
    private final AuthorizedPrincipal caller = TestPrincipals.tenantAdmin(7L, 1L);
    private TerminalQueryService service;

    @BeforeEach
    void setUp() {
        service = new TerminalQueryService(
                mock(TerminalQueryRepository.class), terminals, vehicles);
        when(terminals.findById(DEVICE)).thenReturn(Optional.of(new Terminal(
                DEVICE, "1380000", "JT", "SIMULATOR", 31, 100, "京A99999", 1,
                "JT/T 808-2019/1", "2026-08-24T10:00:00.000+08:00",
                "2026-08-24T10:00:00.000+08:00", "注册", null)));
    }

    /** 自报值只是兜底：前端已经预填过让人确认，这里兜的是直连接口的调用方。 */
    @Test
    void withoutAnExplicitPlateTheReportedOneIsUsedAsAFallback() {
        service.archive(caller, DEVICE, null);

        assertThat(captured().plateNo()).isEqualTo("京A99999");
        assertThat(captured().deviceId()).isEqualTo(DEVICE);
    }

    /** 自报车牌可以是错的——使用者改了就以他填的为准。 */
    @Test
    void anExplicitPlateWinsOverTheReportedOne() {
        service.archive(caller, DEVICE, request("京A12345"));

        assertThat(captured().plateNo()).isEqualTo("京A12345");
    }

    /** 设备号只能来自台账，不能由请求体指定——否则就成了绕过唯一性检查的旁路。 */
    @Test
    void theDeviceIdAlwaysComesFromTheLedgerNotFromTheRequestBody() {
        service.archive(caller, DEVICE, new VehicleService.VehicleRequest(
                "139999999999", "京A12345", null, null, null, null, null, null));

        assertThat(captured().deviceId()).isEqualTo(DEVICE);
    }

    @Test
    void quotaAndOtherProfileRulesStillApply() {
        when(vehicles.create(any(), any())).thenThrow(IamException.quotaExceeded("车辆数量已达套餐上限"));

        assertThatThrownBy(() -> service.archive(caller, DEVICE, request("京A12345")))
                .isInstanceOf(IamException.class).hasMessage("车辆数量已达套餐上限");
    }

    @Test
    void aTerminalThatWasNeverSeenCannotBeArchived() {
        when(terminals.findById("139999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.archive(caller, "139999999999", request("京A12345")))
                .isInstanceOf(IamException.class).hasMessage("终端不存在");
        verify(vehicles, org.mockito.Mockito.never()).create(any(), any());
    }

    @Test
    void theProfileFieldsTheCallerSuppliedAreCarriedThrough() {
        service.archive(caller, DEVICE, new VehicleService.VehicleRequest(
                null, "京A12345", "蓝色", "解放", 4, "工地车", 2L, 9L));

        VehicleService.VehicleRequest forwarded = captured();
        assertThat(forwarded.plateColor()).isEqualTo("蓝色");
        assertThat(forwarded.brand()).isEqualTo("解放");
        assertThat(forwarded.channelCount()).isEqualTo(4);
        assertThat(forwarded.remark()).isEqualTo("工地车");
        assertThat(forwarded.tenantId()).isEqualTo(2L);
        assertThat(forwarded.departmentId()).isEqualTo(9L);
    }

    private VehicleService.VehicleRequest captured() {
        ArgumentCaptor<VehicleService.VehicleRequest> captor =
                ArgumentCaptor.forClass(VehicleService.VehicleRequest.class);
        verify(vehicles, org.mockito.Mockito.atLeastOnce()).create(any(), captor.capture());
        return captor.getValue();
    }

    private static VehicleService.VehicleRequest request(String plateNo) {
        return new VehicleService.VehicleRequest(
                null, plateNo, null, null, null, null, null, null);
    }
}
