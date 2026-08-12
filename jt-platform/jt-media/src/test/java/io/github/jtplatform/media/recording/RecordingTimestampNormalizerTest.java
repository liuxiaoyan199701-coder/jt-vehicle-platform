package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RecordingTimestampNormalizerTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void preservesEpochMicrosAndConvertsEpochMillis() {
        RecordingTimestampNormalizer normalizer = normalizer();

        assertEquals(1_700_000_000_000_000L,
                normalizer.normalize(1_700_000_000_000_000L));
        assertEquals(1_700_000_000_000_000L,
                normalizer.normalize(1_700_000_000_000L));
    }

    @Test
    void decodesLegacyBcdDateInUtc() {
        long bcd = 0x2608101234567890L;
        long expected = LocalDateTime.of(2026, 8, 10, 12, 34, 56, 789_000_000)
                .toInstant(ZoneOffset.UTC)
                .getEpochSecond() * 1_000_000L + 789_000L;

        assertEquals(expected, normalizer().normalize(bcd));
    }

    @Test
    void anchorsRelativeMillisecondsAtFirstArrival() {
        RecordingTimestampNormalizer normalizer = normalizer();
        long receivedUs = RECEIVED_AT.getEpochSecond() * 1_000_000L;

        assertEquals(receivedUs, normalizer.normalize(12_345L));
        assertEquals(receivedUs + 30_000_000L, normalizer.normalize(42_345L));
    }

    private static RecordingTimestampNormalizer normalizer() {
        return new RecordingTimestampNormalizer(Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }
}
