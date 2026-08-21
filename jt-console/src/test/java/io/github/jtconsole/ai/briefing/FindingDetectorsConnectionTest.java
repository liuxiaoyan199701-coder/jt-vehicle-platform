package io.github.jtconsole.ai.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.repository.DriverRepository;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.security.DataScope;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FindingDetectorsConnectionTest {
    private final StatusRepository statuses = mock(StatusRepository.class);
    private final AlarmRepository alarms = mock(AlarmRepository.class);
    private final DailyStatRepository dailyStats = mock(DailyStatRepository.class);
    private final BusinessDateService dates = mock(BusinessDateService.class);
    private final DriverRepository drivers = mock(DriverRepository.class);
    private final ConnectionEventRepository connectionEvents = mock(ConnectionEventRepository.class);
    private final FindingDetectors detectors = new FindingDetectors(
            statuses, alarms, dailyStats, dates, drivers, connectionEvents);

    @BeforeEach
    void unrelatedDetectorsAreEmpty() {
        lenient().when(statuses.findAllLive(any())).thenReturn(List.of());
        lenient().when(statuses.fleetSnapshot(any()))
                .thenReturn(new StatusRepository.FleetSnapshot(0, 0, 0, 0, 0, 0));
        lenient().when(alarms.countCriticalOpen(any())).thenReturn(0L);
        lenient().when(dailyStats.aggregateRange(anyString(), anyString(), any())).thenReturn(List.of());
        lenient().when(dailyStats.totalDistance(anyString(), any())).thenReturn(0D);
        lenient().when(drivers.findExpiringBy(anyString(), any())).thenReturn(List.of());
        lenient().when(dates.today()).thenReturn(LocalDate.of(2026, 8, 21));
        lenient().when(dates.zoneId()).thenReturn(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void threeRegistrationRejectionsProduceWarnFinding() {
        DataScope scope = DataScope.tenantWide(42L);
        when(connectionEvents.countRegistrationFailures(anyString(), anyString(), any()))
                .thenReturn(List.of(new ConnectionEventRepository.RegistrationFailure("device-1", 3)));

        List<DashboardFinding> findings = detectors.detect(scope);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.category()).isEqualTo(DashboardFinding.Category.CONNECTION);
            assertThat(finding.severity()).isEqualTo(DashboardFinding.Severity.WARN);
            assertThat(finding.summary()).contains("device-1").contains("3 次");
        });
        verify(connectionEvents).countRegistrationFailures(anyString(), anyString(), any(DataScope.class));
    }

    @Test
    void noRegistrationRejectionsProduceNoFinding() {
        when(connectionEvents.countRegistrationFailures(anyString(), anyString(), any()))
                .thenReturn(List.of());

        assertThat(detectors.detect(DataScope.tenantWide(42L))).isEmpty();
    }
}
