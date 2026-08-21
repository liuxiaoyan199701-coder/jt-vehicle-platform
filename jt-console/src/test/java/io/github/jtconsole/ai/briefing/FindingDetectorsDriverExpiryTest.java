package io.github.jtconsole.ai.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.github.jtconsole.domain.Driver;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.repository.DriverRepository;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.security.DataScope;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FindingDetectorsDriverExpiryTest {

    @Mock
    private StatusRepository statuses;
    @Mock
    private AlarmRepository alarms;
    @Mock
    private DailyStatRepository dailyStats;
    @Mock
    private BusinessDateService dates;
    @Mock
    private DriverRepository drivers;

    @InjectMocks
    private FindingDetectors detectors;

    @BeforeEach
    void stubUnrelatedDetectors() {
        lenient().when(statuses.findAllLive(any())).thenReturn(List.of());
        lenient().when(statuses.fleetSnapshot(any())).thenReturn(new StatusRepository.FleetSnapshot(0, 0, 0, 0, 0, 0));
        lenient().when(alarms.countCriticalOpen(any())).thenReturn(0L);
        lenient().when(dailyStats.aggregateRange(anyString(), anyString(), any())).thenReturn(List.of());
        lenient().when(dailyStats.totalDistance(anyString(), any())).thenReturn(0.0);
        lenient().when(dates.today()).thenReturn(LocalDate.of(2026, 8, 21));
        lenient().when(dates.zoneId()).thenReturn(java.time.ZoneId.of("Asia/Shanghai"));
    }

    private static Driver driver(long id, String name, String period) {
        return new Driver(id, name, "110101199001011234", "LIC" + id, "机构",
                period, null, null, null, 1L, null, null);
    }

    @Test
    void expiredIsCriticalAndExpiringSoonIsWarn() {
        when(drivers.findExpiringBy(anyString(), any(DataScope.class))).thenReturn(List.of(
                driver(1, "已过期", "2026-08-01"),
                driver(2, "恰好30天", "2026-09-20"),
                driver(3, "20天后", "2026-09-10")));

        List<DashboardFinding> driverFindings = detectors.detect(DataScope.tenantWide(1L)).stream()
                .filter(finding -> finding.category() == DashboardFinding.Category.DRIVER)
                .toList();

        assertThat(driverFindings).hasSize(2);
        assertThat(driverFindings)
                .anySatisfy(finding -> {
                    assertThat(finding.severity()).isEqualTo(DashboardFinding.Severity.CRITICAL);
                    assertThat(finding.summary()).contains("1 名司机").contains("已过期");
                })
                .anySatisfy(finding -> {
                    assertThat(finding.severity()).isEqualTo(DashboardFinding.Severity.WARN);
                    assertThat(finding.summary()).contains("2 名司机").contains("30 天内到期");
                });
    }

    @Test
    void allValidProducesNoDriverFinding() {
        when(drivers.findExpiringBy(anyString(), any(DataScope.class))).thenReturn(List.of());

        List<DashboardFinding> findings = detectors.detect(DataScope.tenantWide(1L));

        assertThat(findings).extracting(DashboardFinding::category)
                .doesNotContain(DashboardFinding.Category.DRIVER);
    }
}
