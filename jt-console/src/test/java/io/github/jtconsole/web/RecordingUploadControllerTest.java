package io.github.jtconsole.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.RecordingUploadRepository;
import io.github.jtconsole.security.DataScope;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class RecordingUploadControllerTest {
    private final RecordingUploadRepository tasks = mock(RecordingUploadRepository.class);
    private final VehicleService vehicles = mock(VehicleService.class);
    private final DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
    private final RestClient gateway = mock(RestClient.class);
    private final RecordingUploadController controller =
            new RecordingUploadController(tasks, vehicles, ownership, gateway);

    @Test
    void outOfScopeCreateDoesNotValidateDetailsOrTouchStorageOrGateway() {
        DataScope scope = DataScope.tenantWide(2);
        when(vehicles.requireVisibleDevice("device-1", scope))
                .thenThrow(IamException.notFound("车辆不存在"));
        var invalidDetails = new RecordingUploadController.CreateRequest(
                "device-1", 0, Instant.parse("2026-08-21T01:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"), 0, 0, 9, 9, 9, 9);

        assertThatThrownBy(() -> controller.create(invalidDetails, scope))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("车辆不存在");
        verifyNoInteractions(tasks, ownership, gateway);
    }

    @Test
    void outOfScopeListDoesNotRevealWhetherTasksExist() {
        DataScope scope = DataScope.tenantWide(2);
        when(vehicles.requireVisibleDevice("device-1", scope))
                .thenThrow(IamException.notFound("车辆不存在"));

        assertThatThrownBy(() -> controller.list("device-1", 50, scope))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("车辆不存在");
        verifyNoInteractions(tasks, gateway);
    }
}
