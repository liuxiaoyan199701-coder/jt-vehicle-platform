package io.github.jtplatform.media.recording;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

final class RecordingTimestampNormalizer {
    private static final long YEAR_2000_EPOCH_MILLIS = 946_684_800_000L;
    private static final long YEAR_2100_EPOCH_MILLIS = 4_102_444_800_000L;
    private static final long YEAR_2000_EPOCH_MICROS = YEAR_2000_EPOCH_MILLIS * 1_000L;
    private static final long YEAR_2100_EPOCH_MICROS = YEAR_2100_EPOCH_MILLIS * 1_000L;

    private final Clock clock;
    private boolean anchored;
    private long anchorRawMillis;
    private long anchorEpochMicros;

    RecordingTimestampNormalizer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    long normalize(long rawTimestamp) {
        Long bcdTimestamp = decodeBcdTimestamp(rawTimestamp);
        if (bcdTimestamp != null) {
            return bcdTimestamp;
        }
        if (rawTimestamp >= YEAR_2000_EPOCH_MICROS
                && rawTimestamp < YEAR_2100_EPOCH_MICROS) {
            return rawTimestamp;
        }
        if (rawTimestamp >= YEAR_2000_EPOCH_MILLIS
                && rawTimestamp < YEAR_2100_EPOCH_MILLIS) {
            return multiplySaturated(rawTimestamp, 1_000L);
        }
        if (!anchored) {
            anchored = true;
            anchorRawMillis = rawTimestamp;
            anchorEpochMicros = epochMicros(clock.instant());
            return anchorEpochMicros;
        }
        long deltaMillis = subtractSaturated(rawTimestamp, anchorRawMillis);
        return addSaturated(anchorEpochMicros, multiplySaturated(deltaMillis, 1_000L));
    }

    private static Long decodeBcdTimestamp(long value) {
        byte[] bytes = new byte[8];
        for (int index = bytes.length - 1; index >= 0; index--) {
            bytes[index] = (byte) value;
            value >>>= 8;
        }
        int[] digits = new int[16];
        for (int index = 0; index < bytes.length; index++) {
            digits[index * 2] = (bytes[index] >>> 4) & 0x0f;
            digits[index * 2 + 1] = bytes[index] & 0x0f;
            if (digits[index * 2] > 9 || digits[index * 2 + 1] > 9) {
                return null;
            }
        }
        if (digits[15] != 0) {
            return null;
        }
        int year = 2000 + pair(digits, 0);
        int month = pair(digits, 2);
        int day = pair(digits, 4);
        int hour = pair(digits, 6);
        int minute = pair(digits, 8);
        int second = pair(digits, 10);
        int millis = digits[12] * 100 + digits[13] * 10 + digits[14];
        try {
            Instant instant = LocalDateTime.of(
                    year, month, day, hour, minute, second, millis * 1_000_000)
                    .toInstant(ZoneOffset.UTC);
            return epochMicros(instant);
        } catch (DateTimeException invalidBcdDate) {
            return null;
        }
    }

    private static int pair(int[] digits, int index) {
        return digits[index] * 10 + digits[index + 1];
    }

    private static long epochMicros(Instant instant) {
        return addSaturated(
                multiplySaturated(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }

    private static long addSaturated(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return right < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static long subtractSaturated(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            return left < right ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static long multiplySaturated(long value, long multiplier) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException overflow) {
            return value < 0 ^ multiplier < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
