package io.github.jtplatform.delivery.channel;

import java.time.Duration;
import java.util.function.LongSupplier;

final class CircuitBreaker {
    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    record Permission(boolean allowed, Duration retryAfter, long generation) {
        static Permission allowedNow(long generation) {
            return new Permission(true, Duration.ZERO, generation);
        }
    }

    private final int failureThreshold;
    private final long openNanos;
    private final long halfOpenWaitNanos;
    private final LongSupplier nanoTime;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openUntil;
    private long generation;

    CircuitBreaker(int failureThreshold, Duration openDuration) {
        this(failureThreshold, openDuration, System::nanoTime);
    }

    CircuitBreaker(int failureThreshold, Duration openDuration, LongSupplier nanoTime) {
        this.failureThreshold = failureThreshold;
        this.openNanos = openDuration.toNanos();
        this.halfOpenWaitNanos = Math.max(1_000_000L, Math.min(openNanos, 10_000_000L));
        this.nanoTime = nanoTime;
    }

    synchronized Permission tryAcquire() {
        long now = nanoTime.getAsLong();
        if (state == State.CLOSED) {
            return Permission.allowedNow(generation);
        }
        if (state == State.OPEN) {
            long remaining = openUntil - now;
            if (remaining > 0) {
                return new Permission(false, Duration.ofNanos(remaining), generation);
            }
            state = State.HALF_OPEN;
            return Permission.allowedNow(generation);
        }
        return new Permission(false, Duration.ofNanos(halfOpenWaitNanos), generation);
    }

    synchronized void recordSuccess(Permission permission) {
        if (!isCurrent(permission)) {
            return;
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
        openUntil = 0;
    }

    synchronized void recordFailure(Permission permission) {
        if (!isCurrent(permission)) {
            return;
        }
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    synchronized State state() {
        return state;
    }

    private void open() {
        state = State.OPEN;
        generation++;
        consecutiveFailures = 0;
        long now = nanoTime.getAsLong();
        openUntil = now > Long.MAX_VALUE - openNanos ? Long.MAX_VALUE : now + openNanos;
    }

    private boolean isCurrent(Permission permission) {
        return permission != null && permission.allowed() && permission.generation() == generation;
    }
}
