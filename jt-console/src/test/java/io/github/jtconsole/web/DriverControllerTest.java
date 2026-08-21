package io.github.jtconsole.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.domain.Driver;
import io.github.jtconsole.operations.DriverService;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.support.TestPrincipals;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 驾驶员身份证号必须由调用者权限决定是否脱敏，不能依赖前端隐藏。 */
class DriverControllerTest {
    private static final String ID_CARD = "110101199001011234";

    private final DriverService drivers = mock(DriverService.class);
    private final DriverController controller = new DriverController(drivers, mock(VehicleService.class));

    @Test
    void viewerGetsMaskedIdCard() {
        var viewer = TestPrincipals.viewer(10, 1);
        when(drivers.search(null, null, viewer.scope(), 1, 20))
                .thenReturn(new DriverService.DriverPage(List.of(driver()), 1));

        Driver returned = controller.list(null, null, 1, 20, viewer.scope(), viewer)
                .data().items().getFirst();

        assertThat(returned.idCard()).isEqualTo("110***********1234");
        assertThat(returned.idCard()).doesNotContain("10119900101");
    }

    @Test
    void callerWithDriverManageGetsFullIdCard() {
        var manager = TestPrincipals.tenantAdmin(11, 1);
        when(drivers.search(null, null, manager.scope(), 1, 20))
                .thenReturn(new DriverService.DriverPage(List.of(driver()), 1));

        Driver returned = controller.list(null, null, 1, 20, manager.scope(), manager)
                .data().items().getFirst();

        assertThat(returned.idCard()).isEqualTo(ID_CARD);
    }

    private static Driver driver() {
        return new Driver(1L, "张三", ID_CARD, "LIC-1", "交管局", "2030-01-01",
                "13800000000", null, null, 1L, null, null);
    }
}
