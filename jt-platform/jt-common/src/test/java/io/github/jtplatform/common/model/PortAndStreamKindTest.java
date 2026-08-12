package io.github.jtplatform.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PortAndStreamKindTest {
    @Test
    void derivesMediaAndSignalPortGroups() {
        assertEquals(new MediaPorts(7810, 7811, 7812, 7813, 7814, 7815, 7816),
                MediaPorts.forInstance(1));
        assertEquals(new MediaPorts(7820, 7821, 7822, 7823, 7824, 7825, 7826),
                MediaPorts.forInstance(2));
        assertEquals(new SignalPorts(7110, 7111, 7112, 7113), SignalPorts.forInstance(1));
    }

    @Test
    void streamKindSeparatesChannelFromTransportMapping() {
        StreamKey key = new StreamKey("device-1", 2, StreamKind.SUB);
        assertEquals(2, key.channel());
        assertEquals(7822, MediaPorts.forInstance(2).ingestPort(key.streamKind()));
        assertEquals(0, key.streamKind().dataType());
        assertEquals(1, key.streamKind().streamType());

        assertEquals(7811, MediaPorts.forInstance(1).ingestPort(StreamKind.MAIN));
        assertEquals(7813, MediaPorts.forInstance(1).ingestPort(StreamKind.PLAYBACK));
        assertEquals(7814, MediaPorts.forInstance(1).ingestPort(StreamKind.TALKBACK));
        assertEquals(2, StreamKind.TALKBACK.dataType());
        assertEquals(0, StreamKind.TALKBACK.streamType());
    }

    @Test
    void rejectsInvalidInstanceNumbersAndChannels() {
        assertThrows(IllegalArgumentException.class, () -> MediaPorts.forInstance(0));
        assertThrows(IllegalArgumentException.class, () -> SignalPorts.forInstance(10));
        assertThrows(IllegalArgumentException.class, () -> new StreamKey("device-1", 0, StreamKind.MAIN));
    }
}
