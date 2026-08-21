package io.github.jtconsole.maintenance;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.audit.AuditRecorder;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.iam.RegistrationService;
import io.github.jtconsole.iam.TenantService;
import io.github.jtconsole.repository.WaybillRepository;
import org.junit.jupiter.api.Test;

class TenancyMaintenanceTasksTest {
    private final TenantService tenants = mock(TenantService.class);
    private final RegistrationService registrations = mock(RegistrationService.class);
    private final AuditRecorder audits = mock(AuditRecorder.class);
    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final ConsoleProperties properties = new ConsoleProperties();

    @Test
    void auditCleanupScheduleAlsoPurgesWaybillsInBatches() {
        properties.getAudit().setCleanupBatchSize(2);
        properties.getAudit().setCleanupMaxBatches(3);
        when(waybills.deleteOlderThan(anyString(), anyInt())).thenReturn(2, 1);
        TenancyMaintenanceTasks tasks = new TenancyMaintenanceTasks(
                tenants, registrations, audits, waybills, properties);

        tasks.purgeAuditLog();

        verify(audits).purgeOlderThan(
                properties.getAudit().getRetention(),
                properties.getAudit().getCleanupBatchSize(),
                properties.getAudit().getCleanupMaxBatches());
        verify(waybills, times(2)).deleteOlderThan(anyString(), anyInt());
    }
}
