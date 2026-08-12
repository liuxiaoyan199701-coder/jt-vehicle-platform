package io.github.jtplatform.media.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.protocol.FragmentFlag;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.media.protocol.Jt1078Header;
import io.github.jtplatform.media.protocol.RtpPacket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class FragmentReassemblerTest {
    private final MutableClock clock = new MutableClock();
    private final FragmentReassembler reassembler =
            new FragmentReassembler(Duration.ofSeconds(2), 1024, clock);

    @Test
    void atomicPacketGoesStraightThrough() {
        var result = reassembler.accept(packet(FragmentFlag.ATOMIC, new byte[] {1, 2}, 10));

        assertTrue(result.isPresent());
        assertArrayEquals(new byte[] {1, 2}, result.orElseThrow().payload());
    }

    @Test
    void firstMiddleAndLastProduceOneCompleteFrame() {
        assertTrue(reassembler.accept(packet(FragmentFlag.FIRST, new byte[] {1, 2}, 10)).isEmpty());
        assertTrue(reassembler.accept(packet(FragmentFlag.MIDDLE, new byte[] {3}, 10)).isEmpty());
        var result = reassembler.accept(packet(FragmentFlag.LAST, new byte[] {4, 5}, 10));

        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, result.orElseThrow().payload());
        assertEquals(0, reassembler.incompleteCount());
    }

    @Test
    void expiredPartialFrameIsDiscardedAndNeverEmitted() {
        assertTrue(reassembler.accept(packet(FragmentFlag.FIRST, new byte[] {1, 2}, 10)).isEmpty());
        clock.advance(Duration.ofSeconds(3));

        assertEquals(1, reassembler.evictExpired());
        assertEquals(0, reassembler.incompleteCount());
        assertTrue(reassembler.accept(packet(FragmentFlag.LAST, new byte[] {3}, 10)).isEmpty());
    }

    @Test
    void fragmentsFromDifferentTimestampsAreNeverMixed() {
        reassembler.accept(packet(FragmentFlag.FIRST, new byte[] {1}, 10));
        reassembler.accept(packet(FragmentFlag.FIRST, new byte[] {2}, 11));

        assertArrayEquals(new byte[] {1, 3},
                reassembler.accept(packet(FragmentFlag.LAST, new byte[] {3}, 10)).orElseThrow().payload());
        assertArrayEquals(new byte[] {2, 4},
                reassembler.accept(packet(FragmentFlag.LAST, new byte[] {4}, 11)).orElseThrow().payload());
    }

    private static RtpPacket packet(FragmentFlag flag, byte[] payload, long timestamp) {
        return new RtpPacket(new Jt1078Header(
                0, Jt1078Constants.PT_H264, 1, "13800138000", 1,
                Jt1078Constants.VIDEO_I_FRAME, flag, timestamp, 0, 0, payload.length),
                StreamKind.MAIN, payload);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-10T00:00:00Z");

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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
