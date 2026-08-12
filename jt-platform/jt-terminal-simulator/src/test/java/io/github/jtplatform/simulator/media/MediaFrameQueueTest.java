package io.github.jtplatform.simulator.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaFrameQueueTest {
    private static final long BASE = Instant.parse("2026-08-11T00:00:00Z").toEpochMilli();

    @Test
    void holdsForSixtyMillisecondsAndReturnsTimestampOrder() {
        MediaStats stats = stats();
        MediaFrameQueue queue = new MediaFrameQueue(8, Duration.ofMillis(60), stats);
        queue.offer(frame(MediaFrameType.AUDIO, BASE + 20));
        queue.offer(frame(MediaFrameType.VIDEO_I, BASE));
        queue.offer(frame(MediaFrameType.VIDEO_P, BASE + 40));

        assertTrue(queue.pollReady(BASE + 59).isEmpty());
        assertEquals(BASE, queue.pollReady(BASE + 60).orElseThrow().timestampMillis());
        assertEquals(BASE + 20, queue.pollReady(BASE + 80).orElseThrow().timestampMillis());
        assertEquals(BASE + 40, queue.pollReady(BASE + 100).orElseThrow().timestampMillis());
    }

    @Test
    void overflowPurgesPFramesAndWaitsForNextIdr() {
        MediaStats stats = stats();
        MediaFrameQueue queue = new MediaFrameQueue(3, stats);
        assertTrue(queue.offer(frame(MediaFrameType.VIDEO_I, BASE)));
        assertTrue(queue.offer(frame(MediaFrameType.VIDEO_P, BASE + 10)));
        assertTrue(queue.offer(frame(MediaFrameType.VIDEO_P, BASE + 20)));

        assertTrue(queue.offer(frame(MediaFrameType.AUDIO, BASE + 21)));
        assertEquals(2, queue.size());
        assertFalse(queue.offer(frame(MediaFrameType.VIDEO_P, BASE + 30)));
        assertTrue(queue.offer(frame(MediaFrameType.VIDEO_I, BASE + 40)));

        MediaStats.Snapshot snapshot = stats.snapshot();
        assertEquals(3, snapshot.droppedPFrames());
        assertEquals(3, snapshot.maximumQueueDepth());
        assertEquals(3, snapshot.queueDepth());
    }

    @Test
    void equalTimestampsRetainInsertionOrderAndStatsTrackSends() {
        MediaStats stats = stats();
        MediaFrameQueue queue = new MediaFrameQueue(4, stats);
        MediaFrame audio = new MediaFrame(MediaFrameType.AUDIO, BASE, new byte[] {1});
        MediaFrame video = new MediaFrame(MediaFrameType.VIDEO_I, BASE, new byte[] {2});
        queue.offer(audio);
        queue.offer(video);

        List<MediaFrame> frames = List.of(queue.pollAny().orElseThrow(), queue.pollAny().orElseThrow());
        frames.forEach(stats::recordSent);

        assertEquals(MediaFrameType.AUDIO, frames.get(0).type());
        assertEquals(MediaFrameType.VIDEO_I, frames.get(1).type());
        assertEquals(1, stats.snapshot().sentAudioFrames());
        assertEquals(1, stats.snapshot().sentVideoFrames());
        assertEquals(2, stats.snapshot().sentBytes());
        assertEquals(0, stats.snapshot().queueDepth());
    }

    private static MediaFrame frame(MediaFrameType type, long timestamp) {
        return new MediaFrame(type, timestamp, new byte[] {(byte) type.ordinal()});
    }

    private static MediaStats stats() {
        return new MediaStats(Clock.fixed(Instant.ofEpochMilli(BASE + 1_000), ZoneOffset.UTC));
    }
}
