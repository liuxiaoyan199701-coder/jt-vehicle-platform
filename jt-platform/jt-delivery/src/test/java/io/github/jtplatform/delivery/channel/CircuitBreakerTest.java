package io.github.jtplatform.delivery.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {
    @Test
    void opensThenAllowsOnlyOneHalfOpenProbe() {
        AtomicLong now = new AtomicLong(1_000_000L);
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofMillis(100), now::get);

        CircuitBreaker.Permission first = breaker.tryAcquire();
        assertTrue(first.allowed());
        breaker.recordFailure(first);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        CircuitBreaker.Permission second = breaker.tryAcquire();
        assertTrue(second.allowed());
        breaker.recordFailure(second);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire().allowed());

        now.addAndGet(Duration.ofMillis(100).toNanos());
        CircuitBreaker.Permission probe = breaker.tryAcquire();
        assertTrue(probe.allowed());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertFalse(breaker.tryAcquire().allowed());
        breaker.recordSuccess(probe);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void staleInFlightSuccessCannotCloseANewerOpenGeneration() {
        AtomicLong now = new AtomicLong();
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(1), now::get);
        CircuitBreaker.Permission staleSuccess = breaker.tryAcquire();
        CircuitBreaker.Permission failingCall = breaker.tryAcquire();

        breaker.recordFailure(failingCall);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        breaker.recordSuccess(staleSuccess);

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire().allowed());
    }
}
