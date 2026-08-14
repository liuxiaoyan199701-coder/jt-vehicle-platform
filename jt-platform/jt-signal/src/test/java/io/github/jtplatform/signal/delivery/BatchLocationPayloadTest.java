package io.github.jtplatform.signal.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0704;

/**
 * 0x0704 的每一项都是完整的位置汇报，投递出去的形态必须与顶层 0x0200 完全一致。
 *
 * <p>通用的 bean 反射只会给出原始值——1e-6 度的整数经纬度、位域形式的报警与状态。下游若拿到的是
 * 原始值，就得自己维护一份 32 项的位名表；那张表随苏标、粤标持续扩展，两份副本必然漂移。
 * 这些用例守住的正是「同一份数据只有一种形态」。
 */
class BatchLocationPayloadTest {

    private final ProtocolPayloadMapper payloadMapper = new ProtocolPayloadMapper();

    @Test
    void batchItemsCarryTheSameShapeAsATopLevelLocation() {
        T0200 sample = location();
        Map<String, Object> expected = payloadMapper.map(sample);

        Map<String, Object> payload = payloadMapper.map(
                new T0704().setTotal(1).setType(1).setItems(List.of(location())));

        Map<?, ?> item = firstItem(payload);
        for (String key : List.of("latitude", "longitude", "speedKph", "direction", "altitude",
                "deviceTime", "alarmFlags", "statusFlags")) {
            assertEquals(expected.get(key), item.get(key), "字段 " + key + " 与顶层 0x0200 不一致");
        }
    }

    @Test
    void batchItemsExposeDecimalDegreesAndNamedFlags() {
        Map<String, Object> payload = payloadMapper.map(
                new T0704().setTotal(1).setType(1).setItems(List.of(location())));

        Map<?, ?> item = firstItem(payload);
        assertEquals(39.912345d, (Double) item.get("latitude"), 0.000001d);
        assertEquals(116.397128d, (Double) item.get("longitude"), 0.000001d);
        assertEquals(65.4f, (Float) item.get("speedKph"), 0.001f);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) item.get("alarmFlags")).get("emergency"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) item.get("statusFlags")).get("accOn"));
    }

    @Test
    void batchItemsDropRawBitFieldsAndUnits() {
        Map<String, Object> payload = payloadMapper.map(
                new T0704().setTotal(1).setType(1).setItems(List.of(location())));

        Map<?, ?> item = firstItem(payload);
        // 原始形态一并留着的话，下游会分不清该读哪一个，迟早有人读错单位。
        for (String key : List.of("warnBit", "statusBit", "lat", "lng", "speed")) {
            assertFalse(item.containsKey(key), "不应保留原始字段 " + key);
        }
    }

    @Test
    void mileageAttributeSurvivesEnrichment() {
        T0200 withMileage = location();
        // 里程走附加信息 0x1（1/10 km），业务侧的里程口径完全依赖它能原样送达
        withMileage.setAttributes(new LinkedHashMap<>(Map.of(0x1, 1234)));

        Map<String, Object> payload = payloadMapper.map(
                new T0704().setTotal(1).setType(1).setItems(List.of(withMileage)));

        Map<?, ?> attributes = (Map<?, ?>) firstItem(payload).get("attributes");
        assertEquals(1234, attributes.get("0x1"));
    }

    @Test
    void everyItemIsEnrichedIndependently() {
        T0200 second = location();
        second.setWarnBit(2).setLatitude(31_230_000);

        Map<String, Object> payload = payloadMapper.map(
                new T0704().setTotal(2).setType(0).setItems(List.of(location(), second)));

        List<?> items = (List<?>) payload.get("items");
        assertEquals(2, items.size());
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        Map<?, ?> other = (Map<?, ?>) items.get(1);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) first.get("alarmFlags")).get("emergency"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) other.get("alarmFlags")).get("emergency"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) other.get("alarmFlags")).get("overspeed"));
        assertEquals(31.23d, (Double) other.get("latitude"), 0.000001d);
    }

    @Test
    void emptyBatchIsLeftAlone() {
        Map<String, Object> payload = payloadMapper.map(new T0704().setTotal(0).setType(0));

        assertTrue(payload.get("items") == null || ((List<?>) payload.get("items")).isEmpty());
    }

    @Test
    void batchLevelFieldsRemainAvailable() {
        Map<String, Object> payload = payloadMapper.map(
                new T0704().setTotal(3).setType(1).setItems(List.of(location())));

        assertEquals(3, payload.get("total"));
        // payload.type（0 正常批量 / 1 盲区补报）与信封层的 type（"location"）同名不同义
        assertEquals(1, payload.get("type"));
    }

    private static Map<?, ?> firstItem(Map<String, Object> payload) {
        List<?> items = (List<?>) payload.get("items");
        assertEquals(1, items.size());
        return assertInstanceOf(Map.class, items.get(0));
    }

    private static T0200 location() {
        T0200 location = new T0200();
        location.setWarnBit(1)
                .setStatusBit(3)
                .setLatitude(39_912_345)
                .setLongitude(116_397_128)
                .setAltitude(312)
                .setSpeed(654)
                .setDirection(99);
        location.setDeviceTime(LocalDateTime.of(2026, 8, 11, 14, 0, 0));
        return location;
    }
}
