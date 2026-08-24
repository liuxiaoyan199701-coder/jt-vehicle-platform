package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.repository.DeviceLogRepository.DeviceLogFilter;
import io.github.jtconsole.security.DataScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceLogRepositoryTest {

    private DeviceLogDatabase database;
    private DeviceLogRepository logs;

    @BeforeEach
    void createDatabase() throws Exception {
        Path file = Files.createTempFile("jt-console-device-log-", ".db");
        Files.deleteIfExists(file);
        file.toFile().deleteOnExit();
        database = new DeviceLogDatabase(file);
        logs = new DeviceLogRepository(database);
    }

    @Test
    void theLogDatabaseBringsUpItsOwnSchemaVersionIndependentOfTheBusinessDatabase() {
        assertThat(database.schemaVersion()).isEqualTo(DeviceLogDatabase.SCHEMA_VERSION);
    }

    @Test
    void aReplayedEnvelopeDoesNotProduceASecondRow() {
        assertThat(logs.insertIgnore(log("evt-1", "device-1", 1L, "UP", 0x0200, "10:00"))).isTrue();
        assertThat(logs.insertIgnore(log("evt-1", "device-1", 1L, "UP", 0x0200, "10:00"))).isFalse();

        assertThat(logs.count(filter("device-1").build(), DataScope.platform())).isEqualTo(1);
    }

    @Test
    void filtersNarrowByDirectionMessageIdKeywordAndTimeRange() {
        logs.insertIgnore(log("evt-up", "device-1", 1L, "UP", 0x0200, "10:00"));
        logs.insertIgnore(log("evt-down", "device-1", 1L, "DOWN", 0x8801, "10:05"));
        logs.insertIgnore(log("evt-conn", "device-1", 1L, "CONNECTION", null, "09:00"));
        logs.insertIgnore(log("evt-other", "device-2", 1L, "UP", 0x0200, "10:00"));

        assertThat(ids(filter("device-1").build())).containsExactly("evt-down", "evt-up", "evt-conn");
        assertThat(ids(filter("device-1").direction("DOWN").build())).containsExactly("evt-down");
        assertThat(ids(filter("device-1").msgId(0x0200).build())).containsExactly("evt-up");
        assertThat(ids(filter("device-1").keyword("0x8801").build())).containsExactly("evt-down");
        assertThat(ids(filter("device-1").start("2026-08-24T09:30:00").end("2026-08-24T10:02:00").build()))
                .containsExactly("evt-up");
    }

    @Test
    void pagingWalksTheWholeResultSetWithoutOverlap() {
        for (int index = 0; index < 5; index++) {
            logs.insertIgnore(log("evt-" + index, "device-1", 1L, "UP", 0x0200, "10:0" + index));
        }

        assertThat(ids(filter("device-1").page(1).pageSize(2).build()))
                .containsExactly("evt-4", "evt-3");
        assertThat(ids(filter("device-1").page(3).pageSize(2).build())).containsExactly("evt-0");
        assertThat(logs.count(filter("device-1").build(), DataScope.platform())).isEqualTo(5);
    }

    /**
     * 日志库没有 vehicle 表可 join，租户收窄只能靠 ingest 时刻写下的 tenant_id；
     * 部门收窄与设备可见性由查询服务在业务库上先判定，这里只守住租户这条硬边界。
     */
    @Test
    void aTenantSeesOnlyItsOwnRowsAndTheUnassignedOnesStayPlatformOnly() {
        logs.insertIgnore(log("evt-t1", "device-1", 1L, "UP", 0x0200, "10:00"));
        logs.insertIgnore(log("evt-t2", "device-1", 2L, "UP", 0x0200, "10:01"));
        logs.insertIgnore(log("evt-none", "device-1", null, "UP", 0x0200, "10:02"));

        assertThat(ids(filter("device-1").build(), DataScope.tenantWide(1L))).containsExactly("evt-t1");
        assertThat(ids(filter("device-1").build(), DataScope.tenantWide(2L))).containsExactly("evt-t2");
        assertThat(ids(filter("device-1").build(), DataScope.platform()))
                .containsExactly("evt-none", "evt-t2", "evt-t1");
        assertThat(ids(filter("device-1").build(), DataScope.platformFilteredBy(2L)))
                .containsExactly("evt-t2");
    }

    @Test
    void anAccountWithoutAnyVisibleDepartmentReadsNothing() {
        logs.insertIgnore(log("evt-t1", "device-1", 1L, "UP", 0x0200, "10:00"));
        DataScope nothing = DataScope.departments(1L, Set.of());

        assertThat(logs.findByDevice(filter("device-1").build(), nothing)).isEmpty();
        assertThat(logs.count(filter("device-1").build(), nothing)).isZero();
    }

    @Test
    void cleanupRemovesOnlyExpiredRowsAndRespectsTheBatchSize() {
        logs.insertIgnore(log("evt-old-1", "device-1", 1L, "UP", 0x0200, "10:00", "2026-08-01"));
        logs.insertIgnore(log("evt-old-2", "device-1", 1L, "UP", 0x0200, "10:01", "2026-08-01"));
        logs.insertIgnore(log("evt-fresh", "device-1", 1L, "UP", 0x0200, "10:02"));
        Instant cutoff = Instant.parse("2026-08-20T00:00:00Z");

        assertThat(logs.deleteOlderThan(cutoff, 1)).isEqualTo(1);
        assertThat(logs.deleteOlderThan(cutoff, 10)).isEqualTo(1);
        assertThat(logs.deleteOlderThan(cutoff, 10)).isZero();
        assertThat(ids(filter("device-1").build())).containsExactly("evt-fresh");
    }

    @Test
    void aBlankDeviceIsRejectedRatherThanSilentlyMatchingEveryDevice() {
        assertThatThrownBy(() -> filter(" ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filter("device-1").page(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> ids(DeviceLogFilter filter) {
        return ids(filter, DataScope.platform());
    }

    private List<String> ids(DeviceLogFilter filter, DataScope scope) {
        return logs.findByDevice(filter, scope).stream().map(DeviceLog::eventId).toList();
    }

    private static FilterBuilder filter(String deviceId) {
        return new FilterBuilder(deviceId);
    }

    private static DeviceLog log(
            String eventId, String deviceId, Long tenantId, String direction,
            Integer msgId, String minuteOfDay) {
        return log(eventId, deviceId, tenantId, direction, msgId, minuteOfDay, "2026-08-24");
    }

    private static DeviceLog log(
            String eventId, String deviceId, Long tenantId, String direction,
            Integer msgId, String minuteOfDay, String date) {
        String summary = msgId == null ? "CONNECTED" : String.format("0x%04X 报文", msgId);
        return new DeviceLog(0, eventId, deviceId, tenantId, direction, msgId, 1,
                date + 'T' + minuteOfDay + ":00.000+08:00", summary,
                msgId == null ? null : "7e0200", "{\"speedKph\":6.0}", false, false, "console-1");
    }

    private static final class FilterBuilder {
        private final String deviceId;
        private String start;
        private String end;
        private String direction;
        private Integer msgId;
        private String keyword;
        private int page = 1;
        private int pageSize = 20;

        private FilterBuilder(String deviceId) {
            this.deviceId = deviceId;
        }

        FilterBuilder start(String value) { start = value; return this; }
        FilterBuilder end(String value) { end = value; return this; }
        FilterBuilder direction(String value) { direction = value; return this; }
        FilterBuilder msgId(Integer value) { msgId = value; return this; }
        FilterBuilder keyword(String value) { keyword = value; return this; }
        FilterBuilder page(int value) { page = value; return this; }
        FilterBuilder pageSize(int value) { pageSize = value; return this; }

        DeviceLogFilter build() {
            return new DeviceLogFilter(deviceId, start, end, direction, msgId, keyword, page, pageSize);
        }
    }
}
