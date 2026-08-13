package io.github.jtplatform.signal.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.port.StreamCommandException;
import io.github.yezhihao.netmc.session.SessionManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.t1078.T9101;
import org.yzh.web.endpoint.CommandResponseTracker;
import org.yzh.web.endpoint.MessageManager;
import org.yzh.protocol.t1078.T9102;
import org.yzh.protocol.t1078.T9201;
import org.yzh.protocol.t1078.T9202;

class SignalStreamCommandHandlerTest {
    private static final MediaTarget TARGET = new MediaTarget("media-2", "203.0.113.8", 7822, 0, 7825);

    @Test
    void liveCommandUsesOnlyTheScheduledMediaTarget() {
        StreamKey key = new StreamKey("device-1", 7, StreamKind.SUB);

        T9101 command = SignalStreamCommandHandler.createLiveCommand(key, TARGET);

        assertEquals("203.0.113.8", command.getIp());
        assertEquals(7822, command.getTcpPort());
        assertEquals(0, command.getUdpPort());
        assertEquals(7, command.getChannelNo());
        assertEquals(StreamKind.SUB.dataType(), command.getMediaType());
        assertEquals(StreamKind.SUB.streamType(), command.getStreamType());
    }

    @Test
    void playbackCommandUsesTargetPortAndExplicitTimeRange() {
        StreamKey key = new StreamKey("device-1", 3, StreamKind.PLAYBACK);

        T9201 command = SignalStreamCommandHandler.createPlaybackCommand(
                key,
                new MediaTarget("media-2", "198.51.100.9", 7823, 0, 7825),
                LocalDateTime.of(2026, 8, 10, 12, 30),
                LocalDateTime.of(2026, 8, 10, 13, 45));

        assertEquals("198.51.100.9", command.getIp());
        assertEquals(7823, command.getTcpPort());
        assertEquals("260810123000", command.getStartTime());
        assertEquals("260810134500", command.getEndTime());
    }

    @Test
    void talkbackCloseUsesDedicatedCloseCommand() {
        T9102 command = SignalStreamCommandHandler.createCloseCommand(
                new StreamKey("device-1", 2, StreamKind.TALKBACK));

        assertEquals(4, command.getCommand());
        assertEquals(2, command.getChannelNo());
    }

    @Test
    void playbackCloseUsesPlaybackControlCommand() {
        T9202 command = SignalStreamCommandHandler.createPlaybackCloseCommand(
                new StreamKey("device-1", 3, StreamKind.PLAYBACK));

        assertEquals(2, command.getPlaybackMode());
        assertEquals(3, command.getChannelNo());
    }

    @Test
    void offlineDeviceFailsExplicitly() {
        SignalStreamCommandHandler handler = new SignalStreamCommandHandler(
                new MessageManager(new SessionManager(), new CommandResponseTracker()),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));

        assertThrows(StreamCommandException.class, () -> handler.openLive(
                new StreamKey("offline-device", 1, StreamKind.MAIN), TARGET));
    }
}
