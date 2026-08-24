package io.github.jtconsole.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.domain.DeviceLogPage;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.DeviceLogQueryService;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.DeviceLogDatabase;
import io.github.jtconsole.repository.DeviceLogRepository;
import io.github.jtconsole.security.DataScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceLogControllerTest {

    private DeviceLogRepository logs;
    private VehicleService vehicles;
    private DeviceLogController controller;

    @BeforeEach
    void setUp() throws Exception {
        Path file = Files.createTempFile("jt-console-device-log-web-", ".db");
        Files.deleteIfExists(file);
        file.toFile().deleteOnExit();
        ConsoleProperties properties = new ConsoleProperties();
        properties.getDeviceLog().setDb(file);
        logs = new DeviceLogRepository(new DeviceLogDatabase(properties));
        vehicles = mock(VehicleService.class);
        when(vehicles.requireVisibleDevice(any(), any(DataScope.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        controller = new DeviceLogController(new DeviceLogQueryService(logs, vehicles));
    }

    @Test
    void aPageCarriesTheItemsAndTheRealTotal() {
        for (int index = 0; index < 3; index++) {
            logs.insertIgnore(log("evt-" + index, "device-1", 1L, "UP", 0x0200, "10:0" + index));
        }

        DeviceLogPage page = controller.search(
                "device-1", null, null, null, null, null, 1, 2, DataScope.tenantWide(1L)).data();

        assertThat(page.items()).extracting(DeviceLog::eventId).containsExactly("evt-2", "evt-1");
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(2);
    }

    /**
     * 模型和人都会写 {@code 0x0200}，库里存的却是 512。只认一种写法的后果不是报错而是
     * 「查不出来还不报错」——看起来就像这台设备没发过这种报文。
     */
    @Test
    void bothHexAndDecimalMessageIdsHitTheSameRows() {
        logs.insertIgnore(log("evt-loc", "device-1", 1L, "UP", 0x0200, "10:00"));
        logs.insertIgnore(log("evt-photo", "device-1", 1L, "DOWN", 0x8801, "10:01"));
        DataScope scope = DataScope.tenantWide(1L);

        assertThat(eventIds(controller.search(
                "device-1", null, null, null, "0x0200", null, 1, 20, scope).data()))
                .containsExactly("evt-loc");
        assertThat(eventIds(controller.search(
                "device-1", null, null, null, "512", null, 1, 20, scope).data()))
                .containsExactly("evt-loc");
        assertThat(eventIds(controller.search(
                "device-1", null, null, null, "0X8801", null, 1, 20, scope).data()))
                .containsExactly("evt-photo");
    }

    @Test
    void malformedPagingAndFiltersAreRejectedInsteadOfSilentlyIgnored() {
        DataScope scope = DataScope.tenantWide(1L);

        assertThatThrownBy(() -> controller.search(
                "device-1", null, null, null, null, null, 0, 20, scope))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.search(
                "device-1", null, null, null, null, null, 1, 201, scope))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.search(
                "device-1", null, null, "SIDEWAYS", null, null, 1, 20, scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("方向");
        assertThatThrownBy(() -> controller.search(
                "device-1", null, null, null, "两百", null, 1, 20, scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0x0200");
    }

    /** 越权与不存在必须给出完全一致的回答，否则接口本身就成了跨租户设备的探测器。 */
    @Test
    void aForeignDeviceIsIndistinguishableFromAMissingOne() {
        doThrow(IamException.notFound("车辆不存在"))
                .when(vehicles).requireVisibleDevice(eq("foreign"), any(DataScope.class));
        logs.insertIgnore(log("evt-foreign", "foreign", 2L, "UP", 0x0200, "10:00"));

        assertThatThrownBy(() -> controller.search(
                "foreign", null, null, null, null, null, 1, 20, DataScope.tenantWide(1L)))
                .isInstanceOf(IamException.class).hasMessage("车辆不存在");
    }

    /** 归属对不上时即使拿到了主键也读不到内容——detail 接口不能成为旁路。 */
    @Test
    void aRowOutsideTheScopeCannotBeReadByItsPrimaryKey() {
        logs.insertIgnore(log("evt-other", "device-9", 2L, "UP", 0x0200, "10:00"));
        long id = logs.findById(1L, DataScope.platform()).orElseThrow().id();

        assertThat(controller.get(id, DataScope.tenantWide(1L)).code()).isEqualTo("4004");
        assertThat(controller.get(id, DataScope.tenantWide(2L)).data().eventId())
                .isEqualTo("evt-other");
    }

    @Test
    void platformAdminsCanReadTheLogsOfDevicesThatNeverGotProfiled() {
        logs.insertIgnore(log("evt-stranger", "stranger", null, "UP", 0x0200, "10:00"));

        assertThat(eventIds(controller.search(
                "stranger", null, null, null, null, null, 1, 20, DataScope.platform()).data()))
                .containsExactly("evt-stranger");
    }

    private static List<String> eventIds(DeviceLogPage page) {
        return page.items().stream().map(DeviceLog::eventId).toList();
    }

    private static DeviceLog log(
            String eventId, String deviceId, Long tenantId, String direction,
            Integer msgId, String minuteOfDay) {
        return new DeviceLog(0, eventId, deviceId, tenantId, direction, msgId, 1,
                "2026-08-24T" + minuteOfDay + ":00.000+08:00",
                String.format("0x%04X 报文", msgId), "7e0200", "{\"speedKph\":6.0}",
                false, false, "console-1");
    }
}
