package io.github.jtplatform.simulator.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MediaTimelineAndAudioTest {
    @Test
    void epochTimelineAdvancesOnlyWithMonotonicClock() {
        AtomicLong nanos = new AtomicLong(5_000_000_000L);
        long epoch = Instant.parse("2026-08-11T00:00:00Z").toEpochMilli();
        MediaTimeline timeline = new MediaTimeline(
                Clock.fixed(Instant.ofEpochMilli(epoch), ZoneOffset.UTC), nanos::get);

        assertEquals(epoch, timeline.nowMillis());
        nanos.addAndGet(37_900_000L);
        assertEquals(epoch + 37, timeline.nowMillis());
        nanos.addAndGet(-20_000_000L);
        assertEquals(epoch + 37, timeline.nowMillis());
    }

    @Test
    void framesG711aAt160BytesAndTwentyMillisecondTimestamps() {
        AtomicLong nanos = new AtomicLong();
        long epoch = Instant.parse("2026-08-11T00:00:00Z").toEpochMilli();
        MediaTimeline timeline = new MediaTimeline(
                Clock.fixed(Instant.ofEpochMilli(epoch), ZoneOffset.UTC), nanos::get);
        G711AFrameParser parser = new G711AFrameParser(timeline);
        byte[] audio = new byte[320];
        for (int index = 0; index < audio.length; index++) {
            audio[index] = (byte) index;
        }

        assertEquals(0, parser.accept(audio, 0, 73).size());
        List<MediaFrame> frames = parser.accept(audio, 73, audio.length - 73);

        assertEquals(2, frames.size());
        assertEquals(epoch, frames.get(0).timestampMillis());
        assertEquals(epoch + 20, frames.get(1).timestampMillis());
        assertEquals(MediaFrameType.AUDIO, frames.get(0).type());
        assertArrayEquals(Arrays.copyOfRange(audio, 0, 160), frames.get(0).payload());
        assertArrayEquals(Arrays.copyOfRange(audio, 160, 320), frames.get(1).payload());
        assertEquals(0, parser.pendingBytes());
    }
}
