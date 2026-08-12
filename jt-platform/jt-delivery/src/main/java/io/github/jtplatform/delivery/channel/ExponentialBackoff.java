package io.github.jtplatform.delivery.channel;

import java.time.Duration;
import java.util.Objects;

public final class ExponentialBackoff {
    private final Duration initialDelay;
    private final Duration maximumDelay;

    public ExponentialBackoff(Duration initialDelay, Duration maximumDelay) {
        this.initialDelay = positive(initialDelay, "initialDelay");
        this.maximumDelay = positive(maximumDelay, "maximumDelay");
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay must not be shorter than initialDelay");
        }
    }

    public Duration delayForRetry(int retryNumber) {
        if (retryNumber < 1) {
            throw new IllegalArgumentException("retryNumber must be positive");
        }
        int shift = Math.min(retryNumber - 1, 62);
        long factor = 1L << shift;
        try {
            Duration candidate = initialDelay.multipliedBy(factor);
            return candidate.compareTo(maximumDelay) > 0 ? maximumDelay : candidate;
        } catch (ArithmeticException overflow) {
            return maximumDelay;
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
