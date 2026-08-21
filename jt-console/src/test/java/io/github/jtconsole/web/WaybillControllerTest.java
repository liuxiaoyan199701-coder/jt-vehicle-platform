package io.github.jtconsole.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.WaybillRepository;
import io.github.jtconsole.security.DataScope;
import org.junit.jupiter.api.Test;

class WaybillControllerTest {
    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final VehicleService vehicles = mock(VehicleService.class);
    private final WaybillController controller = new WaybillController(waybills, vehicles);

    @Test
    void outOfScopeListDoesNotTouchWaybillRepository() {
        DataScope scope = DataScope.tenantWide(2);
        when(vehicles.requireVisibleDevice("device-1", scope))
                .thenThrow(IamException.notFound("车辆不存在"));

        assertThatThrownBy(() -> controller.list("device-1", 1, 20, scope))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("车辆不存在");
        verifyNoInteractions(waybills);
    }

    @Test
    void outOfScopeRawDoesNotTouchWaybillRepository() {
        DataScope scope = DataScope.tenantWide(2);
        when(vehicles.requireVisibleDevice("device-1", scope))
                .thenThrow(IamException.notFound("车辆不存在"));

        assertThatThrownBy(() -> controller.raw("device-1", 8, scope))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("车辆不存在");
        verifyNoInteractions(waybills);
    }
}
