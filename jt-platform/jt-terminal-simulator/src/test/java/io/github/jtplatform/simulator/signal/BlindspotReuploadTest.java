package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.BlindspotSegment;
import io.github.jtplatform.simulator.config.TripConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0704;

class BlindspotReuploadTest {
    @Test
    void bufferDropsOldestAndDrainsInInsertionOrder() {
        BlindspotBuffer buffer = new BlindspotBuffer(2);
        buffer.add(point(1));
        buffer.add(point(2));
        buffer.add(point(3));
        assertEquals(2, buffer.size());
        assertEquals(List.of(2, 3), buffer.drain().stream().map(T0200::getSpeed).toList());
        assertEquals(0, buffer.size());
    }

    @Test
    void t0704BlindspotBatchRoundTripsOrderedPoints() throws Exception {
        List<T0200> points = List.of(point(1), point(2), point(3));
        T0704 batch = new T0704().setTotal(points.size()).setType(1).setItems(points);
        batch.setMessageId(org.yzh.protocol.commons.JT808.定位数据批量上传);
        Jt808MessageCodec codec = new Jt808MessageCodec();
        JTMessage decoded = codec.decode(codec.encode(batch));
        T0704 roundTrip = (T0704) decoded;
        assertEquals(1, roundTrip.getType());
        assertEquals(List.of(1, 2, 3), roundTrip.getItems().stream().map(T0200::getSpeed).toList());
    }

    @Test
    void tripConfigAcceptsMultipleNonOverlappingBlindspots() {
        TripConfig config = new TripConfig(false, "", null, null, null, null, 60, 10, true,
                List.of(new BlindspotSegment(10, 20), new BlindspotSegment(40, 50)));
        assertEquals(2, config.blindspots().size());
        assertTrue(config.blindspots().getFirst().endPercent() < config.blindspots().getLast().startPercent());
    }

    private static T0200 point(int speed) {
        return new T0200().setSpeed(speed).setDeviceTime(LocalDateTime.of(2026, 8, 21, 10, speed, 0));
    }
}
