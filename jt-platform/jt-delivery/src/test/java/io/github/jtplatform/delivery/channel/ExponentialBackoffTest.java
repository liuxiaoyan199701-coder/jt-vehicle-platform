package io.github.jtplatform.delivery.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExponentialBackoffTest {
    @Test
    void doublesAndCapsDelay() {
        ExponentialBackoff backoff = new ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(35));

        assertEquals(Duration.ofMillis(10), backoff.delayForRetry(1));
        assertEquals(Duration.ofMillis(20), backoff.delayForRetry(2));
        assertEquals(Duration.ofMillis(35), backoff.delayForRetry(3));
        assertEquals(Duration.ofMillis(35), backoff.delayForRetry(Integer.MAX_VALUE));
    }
}
