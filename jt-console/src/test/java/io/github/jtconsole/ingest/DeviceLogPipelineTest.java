package io.github.jtconsole.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.domain.DeviceLogPage;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.operations.DeviceLogQueryService;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.repository.DeviceLogDatabase;
import io.github.jtconsole.repository.DeviceLogRepository;
import io.github.jtconsole.repository.EventRepository;
import io.github.jtconsole.security.DataScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 控制台侧的闭环：网关信封 → 真实日志库 → 查询服务。
 *
 * <p>把 {@code DeviceLogEndToEndTest}（证明网关发出了正确的信封）另一半接上——两个测试合起来
 * 覆盖「设备发报文」到「页面查得到」的整条链路。这里用的 payload 形状与网关侧那个测试断言的
 * 完全一致，任一侧改了字段名，两边必有一处红。
 */
class DeviceLogPipelineTest {

    private static final String DEVICE = "13800138000";

    private DeviceLogRepository logs;
    private EventIngestionService ingestion;
    private ConnectionEventIngestionService connections;
    private DeviceLogQueryService query;

    @BeforeEach
    void setUp() throws Exception {
        Path file = Files.createTempFile("jt-console-device-log-pipeline-", ".db");
        Files.deleteIfExists(file);
        file.toFile().deleteOnExit();
        ConsoleProperties properties = new ConsoleProperties();
        properties.getDeviceLog().setDb(file);
        logs = new DeviceLogRepository(new DeviceLogDatabase(properties));

        DeviceOwnershipCache ownership = mock(DeviceOwnershipCache.class);
        when(ownership.find(anyString())).thenReturn(Optional.empty());
        when(ownership.find(DEVICE)).thenReturn(
                Optional.of(new DeviceOwnershipCache.Ownership(1L, null)));
        connections = new ConnectionEventIngestionService(
                mock(ConnectionEventRepository.class), ownership, logs);
        ingestion = new EventIngestionService(
                mock(EventRepository.class), mock(LocationService.class),
                mock(MediaIngestionService.class), mock(DriverIdentityIngestionService.class),
                mock(WaybillIngestionService.class), mock(RecordingUploadIngestionService.class),
                connections, new DeviceLogIngestionService(logs, ownership));

        VehicleService vehicles = mock(VehicleService.class);
        when(vehicles.requireVisibleDevice(any(), any(DataScope.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        query = new DeviceLogQueryService(logs, vehicles);
    }

    @Test
    void aLocationReportBecomesAQueryableUpRowWithItsRawFrameAndParsedBody() {
        ingestion.ingest(deviceLog("evt-up", 0x0200L, payload(
                "UP", "0x0200", "7e0200002e0138001380000003", "{\"speedKph\":6.0}", "0", "0")));

        DeviceLog row = onlyRow(query.query(DEVICE, null, null, "UP", "0x0200", null, 1, 20, tenant()));
        assertThat(row.rawHex()).isEqualTo("7e0200002e0138001380000003");
        assertThat(row.parsedJson()).contains("speedKph");
        assertThat(row.msgIdHex()).isEqualTo("0x0200");
        assertThat(row.decodeError()).isFalse();
    }

    @Test
    void aPhotoCommandBecomesAQueryableDownRow() {
        ingestion.ingest(deviceLog("evt-down", 0x8801L, payload(
                "DOWN", "0x8801", "7e8801001301380013800000097e", "{\"channelId\":1}", "0", "0")));

        DeviceLog row = onlyRow(query.query(DEVICE, null, null, "DOWN", null, null, 1, 20, tenant()));
        assertThat(row.msgIdHex()).isEqualTo("0x8801");
        assertThat(row.parsedJson()).contains("channelId");
    }

    @Test
    void aConnectionEventJoinsTheSameTimelineThroughTheMirror() {
        connections.handle(new MessageEnvelope("conn-1", DEVICE, 0x10001L, 1, "JT/T 808-2019",
                "2026-08-24T01:02:03Z", "signal-1", "connection",
                Map.of("kind", "CONNECTED", "eventTime", "2026-08-24T01:02:03Z")));

        DeviceLog row = onlyRow(query.query(DEVICE, null, null, "CONNECTION", null, null, 1, 20, tenant()));
        assertThat(row.summary()).contains("CONNECTED");
        assertThat(row.rawHex()).isNull();
        assertThat(row.msgId()).isNull();
    }

    @Test
    void aMalformedFrameIsQueryableWithItsRawBytesAndTheDecodeErrorFlag() {
        ingestion.ingest(deviceLog("evt-bad", 0L, payload(
                "UP", "", "00017e", "", "1", "0")));

        DeviceLog row = onlyRow(query.query(DEVICE, null, null, null, null, null, 1, 20, tenant()));
        assertThat(row.decodeError()).isTrue();
        assertThat(row.rawHex()).isEqualTo("00017e");
        assertThat(row.parsedJson()).isNull();
        assertThat(row.msgId()).isNull();
    }

    /** 三个方向落在同一张表上，一次查询就能看到设备的完整时间线。 */
    @Test
    void allThreeDirectionsShareOneTimelineOrderedNewestFirst() {
        ingestion.ingest(deviceLog("evt-1", 0x0200L, payload(
                "UP", "0x0200", "7e0200", "{}", "0", "0", "2026-08-24T01:00:00Z")));
        ingestion.ingest(deviceLog("evt-2", 0x8801L, payload(
                "DOWN", "0x8801", "7e8801", "{}", "0", "0", "2026-08-24T01:01:00Z")));
        connections.handle(new MessageEnvelope("conn-1", DEVICE, 0x10001L, 1, "JT/T 808-2019",
                "2026-08-24T00:59:00Z", "signal-1", "connection",
                Map.of("kind", "CONNECTED", "eventTime", "2026-08-24T00:59:00Z")));

        DeviceLogPage page = query.query(DEVICE, null, null, null, null, null, 1, 20, tenant());
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(DeviceLog::direction)
                .containsExactly("DOWN", "UP", "CONNECTION");
    }

    private static DataScope tenant() {
        return DataScope.tenantWide(1L);
    }

    private static DeviceLog onlyRow(DeviceLogPage page) {
        assertThat(page.items()).hasSize(1);
        return page.items().getFirst();
    }

    private static MessageEnvelope deviceLog(
            String eventId, long messageId, Map<String, Object> payload) {
        return new MessageEnvelope(eventId, DEVICE, messageId, 3, "JT/T 808-2019",
                "2026-08-24T01:02:03Z", "signal-1", "device_log", payload);
    }

    private static Map<String, Object> payload(
            String direction, String msgIdHex, String rawHex, String parsedJson,
            String decodeError, String truncated) {
        return payload(direction, msgIdHex, rawHex, parsedJson, decodeError, truncated,
                "2026-08-24T01:02:03Z");
    }

    /** 键名与取值形态与网关 {@code DeliveringMessageLogEmitter} 发出的完全一致。 */
    private static Map<String, Object> payload(
            String direction, String msgIdHex, String rawHex, String parsedJson,
            String decodeError, String truncated, String logTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("direction", direction);
        payload.put("msgIdHex", msgIdHex);
        payload.put("serialNo", "3");
        payload.put("summary", "位置信息汇报");
        payload.put("rawHex", rawHex);
        payload.put("parsedJson", parsedJson);
        payload.put("decodeError", decodeError);
        payload.put("truncated", truncated);
        payload.put("logTime", logTime);
        return payload;
    }
}
