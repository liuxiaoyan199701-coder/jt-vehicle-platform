package io.github.jtconsole.operations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.security.DataScope;
import org.junit.jupiter.api.Test;

class ConnectionDiagnosticsServiceTest {
    @Test
    void tenantMustPassVehicleVisibilityBeforeReadingEvents() {
        ConnectionEventRepository events = mock(ConnectionEventRepository.class);
        VehicleService vehicles = mock(VehicleService.class);
        ConnectionDiagnosticsService service = new ConnectionDiagnosticsService(events, vehicles);

        org.mockito.Mockito.doThrow(io.github.jtconsole.iam.IamException.notFound("车辆不存在"))
                .when(vehicles).requireVisibleDevice(
                        org.mockito.ArgumentMatchers.eq("foreign"),
                        org.mockito.ArgumentMatchers.any(DataScope.class));
        assertThrows(io.github.jtconsole.iam.IamException.class,
                () -> service.query("foreign", null, null, 1, 50, DataScope.tenantWide(2)));
        verify(events, org.mockito.Mockito.never()).findByDevice(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }
}
