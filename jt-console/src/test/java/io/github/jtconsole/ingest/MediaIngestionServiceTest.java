package io.github.jtconsole.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.geo.CoordTransform;
import io.github.jtconsole.repository.MediaRepository;
import io.github.jtconsole.security.DataScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 抓拍位置的入库口径。
 *
 * <p><b>为什么值得单独测</b>：网关一直在 0x0801 的信封里带 {@code location}，但控制台此前把它丢了。
 * 丢弃是静默的——照片照常入库、列表照常显示，只是永远没有位置。这类「少了一样东西但一切正常」
 * 的缺陷，只有断言才拦得住。
 *
 * <p>另一半是「没定位时必须写 null 而不是 0」。写 0 的后果是地图上多出一个位于几内亚湾的点，
 * 而它看起来和真实数据毫无区别。
 */
class MediaIngestionServiceTest {

    /** 捕获写入内容的仓储替身。真库在这里没有价值——被测的是字段映射，不是 SQL。 */
    private static final class CapturingRepository extends MediaRepository {

        private final List<MediaFile> saved = new ArrayList<>();

        CapturingRepository() {
            super(null);
        }

        @Override
        public void insertIgnore(MediaFile file) {
            saved.add(file);
        }

        @Override
        public List<MediaFile> findRecentByDevice(String deviceId, int limit, DataScope scope) {
            return List.of();
        }
    }

    private static MessageEnvelope envelope(Map<String, Object> payload) {
        return new MessageEnvelope(
                "evt-1", "013800138000", 0x0801L, 1, "2019",
                "2026-08-18T10:00:00.000+08:00", "signal-1", "other", payload);
    }

    private static Map<String, Object> basePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileId", 4096L);
        payload.put("fileType", "image");
        payload.put("fileFormat", "jpg");
        payload.put("fileName", "013800138000-4096.jpg");
        payload.put("size", 51_200L);
        payload.put("accessAddress", "/files/multimedia/013800138000/4096.jpg");
        payload.put("channelId", 1);
        payload.put("event", 0);
        return payload;
    }

    @Test
    void storesLocationAndConvertsToGcj() {
        CapturingRepository repository = new CapturingRepository();
        MediaIngestionService service = new MediaIngestionService(repository);

        Map<String, Object> payload = basePayload();
        payload.put("location", Map.of("latitude", 31.230416D, "longitude", 121.473701D));

        assertTrue(service.handleIfMediaUpload(envelope(payload)));

        assertEquals(1, repository.saved.size());
        MediaFile stored = repository.saved.getFirst();
        assertTrue(stored.locatable());
        assertEquals(31.230416D, stored.lat(), 1e-9);
        assertEquals(121.473701D, stored.lng(), 1e-9);

        // GCJ 必须真的经过偏移，且与平台其它位置走同一个换算——否则抓拍点会和轨迹点错开。
        double[] expected = CoordTransform.wgs84ToGcj02(31.230416D, 121.473701D);
        assertEquals(expected[0], stored.gcjLat(), 1e-9);
        assertEquals(expected[1], stored.gcjLng(), 1e-9);
        assertNotNull(stored.gcjLat());
    }

    @Test
    void keepsNullWhenThereIsNoLocation() {
        CapturingRepository repository = new CapturingRepository();
        MediaIngestionService service = new MediaIngestionService(repository);

        assertTrue(service.handleIfMediaUpload(envelope(basePayload())));

        MediaFile stored = repository.saved.getFirst();
        assertFalse(stored.locatable());
        assertNull(stored.lat());
        assertNull(stored.lng());
        assertNull(stored.gcjLat());
        assertNull(stored.gcjLng());
    }

    /**
     * (0,0) 是「设备没定位」的常见表现，不是几内亚湾。
     *
     * <p>放行它的代价很具体：地图上会出现一个位于非洲西侧海面的抓拍点，而它和真实数据长得
     * 一模一样，没有人会怀疑。
     */
    @Test
    void treatsZeroCoordinatesAsUnlocated() {
        CapturingRepository repository = new CapturingRepository();
        MediaIngestionService service = new MediaIngestionService(repository);

        Map<String, Object> payload = basePayload();
        payload.put("location", Map.of("latitude", 0.0D, "longitude", 0.0D));

        service.handleIfMediaUpload(envelope(payload));

        MediaFile stored = repository.saved.getFirst();
        assertFalse(stored.locatable());
        assertNull(stored.gcjLat());
    }

    @Test
    void treatsOutOfRangeCoordinatesAsUnlocated() {
        CapturingRepository repository = new CapturingRepository();
        MediaIngestionService service = new MediaIngestionService(repository);

        Map<String, Object> payload = basePayload();
        payload.put("location", Map.of("latitude", 991.0D, "longitude", 121.0D));

        service.handleIfMediaUpload(envelope(payload));

        assertFalse(repository.saved.getFirst().locatable());
    }

    @Test
    void reportsAlarmTriggeredForEventCodeTwoAndAbove() {
        CapturingRepository repository = new CapturingRepository();
        MediaIngestionService service = new MediaIngestionService(repository);

        Map<String, Object> payload = basePayload();
        payload.put("event", 3);   // 碰撞侧翻报警触发

        service.handleIfMediaUpload(envelope(payload));

        assertTrue(repository.saved.getFirst().alarmTriggered());
    }

    /**
     * 时间戳必须归一化到东八区，与其它投递路径一致。
     *
     * <p><b>线上踩过</b>：网关送来的是 {@code Instant.toString()}，即带 Z 后缀的 UTC、纳秒精度。
     * 原样入库后界面差 8 小时，而更隐蔽的是——时间列是字符串，{@code TimeBounds} 把查询边界
     * 归一化成 {@code +08:00}，字典序对不上，多媒体页按时间段筛会**静默少查**，
     * 告警时段联查永远为空，全程不报任何错。
     */
    @Test
    void normalisesUtcTimestampsToBeijingTime() {
        CapturingRepository repository = new CapturingRepository();
        MediaIngestionService service = new MediaIngestionService(repository);

        MessageEnvelope utc = new MessageEnvelope(
                "evt-3", "013800138000", 0x0801L, 1, "2019",
                // 网关的原始形态：UTC、纳秒
                "2026-08-18T06:59:31.897793547Z", "signal-1", "other", basePayload());

        service.handleIfMediaUpload(utc);

        String stored = repository.saved.getFirst().capturedAt();
        assertTrue(stored.endsWith("+08:00"), "应归一化到东八区，实际 " + stored);
        assertTrue(stored.startsWith("2026-08-18T14:59:31"), "UTC 06:59 应换算为北京时间 14:59，实际 " + stored);
    }

    /** 已经是东八区的输入不该被再次换算。 */
    @Test
    void leavesBeijingTimestampsUnchanged() {
        CapturingRepository repository = new CapturingRepository();
        MediaIngestionService service = new MediaIngestionService(repository);

        MessageEnvelope local = new MessageEnvelope(
                "evt-4", "013800138000", 0x0801L, 1, "2019",
                "2026-08-13T13:32:26.000+08:00", "signal-1", "other", basePayload());

        service.handleIfMediaUpload(local);

        assertTrue(repository.saved.getFirst().capturedAt().startsWith("2026-08-13T13:32:26"));
    }

    @Test
    void ignoresMessagesThatAreNotMultimediaUploads() {
        CapturingRepository repository = new CapturingRepository();
        MediaIngestionService service = new MediaIngestionService(repository);

        MessageEnvelope location = new MessageEnvelope(
                "evt-2", "013800138000", 0x0200L, 1, "2019",
                "2026-08-18T10:00:00.000+08:00", "signal-1", "location", Map.of());

        assertFalse(service.handleIfMediaUpload(location));
        assertTrue(repository.saved.isEmpty());
    }
}
