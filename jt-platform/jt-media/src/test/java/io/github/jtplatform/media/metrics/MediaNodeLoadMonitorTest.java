package io.github.jtplatform.media.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MediaNodeLoadMonitorTest {
    @Test
    void samplesDistinctIngressCountAndActualWrittenBytesAsBitsPerSecond() {
        AtomicInteger activeIngressStreams = new AtomicInteger();
        AtomicLong writtenBytes = new AtomicLong();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        MediaNodeLoadMonitor monitor = new MediaNodeLoadMonitor(
                activeIngressStreams::get, writtenBytes::get, clock);

        activeIngressStreams.set(2);
        writtenBytes.set(250);
        clock.advanceSeconds(2);

        assertEquals(new MediaNodeLoadMonitor.LoadSnapshot(2, 1_000), monitor.sample());
        activeIngressStreams.set(3);
        assertEquals(3, monitor.snapshot().currentStreams());
        assertEquals(1_000, monitor.snapshot().outboundBitsPerSecond());
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
