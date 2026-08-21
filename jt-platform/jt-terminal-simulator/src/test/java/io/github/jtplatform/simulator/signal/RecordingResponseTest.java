package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jtplatform.simulator.config.RecordingConfig;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t1078.T1205;
import org.yzh.protocol.t1078.T9201;

class RecordingResponseTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:15:00Z");

    @Test
    void t1205ResourceListEncodesAndDecodesWithEastEightLocalTimes() throws Exception {
        RecordingResourceGenerator generator = new RecordingResourceGenerator();
        List<T1205.Item> items = generator.generate(
                new RecordingConfig(2, "NOW-2H", "NOW", 3), NOW);
        assertEquals(2, items.size());
        assertEquals(LocalDateTime.of(2026, 8, 20, 16, 15), items.getFirst().getStartTime());
        assertEquals(LocalDateTime.of(2026, 8, 20, 17, 15), items.getFirst().getEndTime());
        assertEquals(3, items.getFirst().getChannelNo());

        T1205 response = new T1205().setResponseSerialNo(27).setItems(items);
        response.setMessageId(org.yzh.protocol.commons.JT1078.终端上传音视频资源列表);
        Jt808MessageCodec codec = new Jt808MessageCodec();
        JTMessage decoded = codec.decode(codec.encode(response));
        T1205 roundTrip = assertInstanceOf(T1205.class, decoded);
        assertEquals(27, roundTrip.getResponseSerialNo());
        assertEquals(items.size(), roundTrip.getItems().size());
        assertEquals(items.get(1).getStartTime(), roundTrip.getItems().get(1).getStartTime());
        assertEquals(items.get(1).getEndTime(), roundTrip.getItems().get(1).getEndTime());
    }

    @Test
    void resourceGeneratorHandlesZeroCountAndCrossMidnight() {
        RecordingResourceGenerator generator = new RecordingResourceGenerator();
        assertEquals(List.of(), generator.generate(
                new RecordingConfig(0, "23:30", "23:45", 1), NOW));

        List<T1205.Item> items = generator.generate(
                new RecordingConfig(2, "23:30", "00:30", 1), NOW);
        assertEquals(2, items.size());
        assertEquals(LocalDateTime.of(2026, 8, 20, 23, 30), items.getFirst().getStartTime());
        assertEquals(LocalDateTime.of(2026, 8, 21, 0, 0), items.getFirst().getEndTime());
        assertEquals(LocalDateTime.of(2026, 8, 21, 0, 30), items.getLast().getEndTime());
    }

    @Test
    void playbackRequestKeepsTargetPortsChannelAndProtocolTimeRange() {
        T9201 command = new T9201()
                .setIp("192.0.2.44")
                .setTcpPort(7801)
                .setUdpPort(7802)
                .setChannelNo(7)
                .setMediaType(2)
                .setStreamType(1)
                .setStartTime("260820161500")
                .setEndTime("260820163000");

        RecordingPlaybackRequest request = RecordingPlaybackRequest.from(command);
        assertEquals("192.0.2.44", request.host());
        assertEquals(7801, request.tcpPort());
        assertEquals(7802, request.udpPort());
        assertEquals(7, request.channel());
        assertEquals(LocalDateTime.of(2026, 8, 20, 16, 15), request.startTime());
        assertEquals(LocalDateTime.of(2026, 8, 20, 16, 30), request.endTime());
    }

    @Test
    void playbackRequestRejectsInvalidTargetAndRange() {
        T9201 invalidPort = new T9201().setIp("192.0.2.44").setTcpPort(0).setChannelNo(1)
                .setStartTime("260820161500").setEndTime("260820163000");
        assertThrows(IllegalArgumentException.class, () -> RecordingPlaybackRequest.from(invalidPort));

        T9201 invalidRange = new T9201().setIp("192.0.2.44").setTcpPort(7801).setChannelNo(1)
                .setStartTime("260820163000").setEndTime("260820161500");
        assertThrows(IllegalArgumentException.class, () -> RecordingPlaybackRequest.from(invalidRange));
    }
}
