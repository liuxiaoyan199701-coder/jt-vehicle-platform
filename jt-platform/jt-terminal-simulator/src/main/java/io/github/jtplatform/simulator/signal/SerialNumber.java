package io.github.jtplatform.simulator.signal;

import java.util.concurrent.atomic.AtomicInteger;

public final class SerialNumber {
    private final AtomicInteger lastIssued;

    public SerialNumber() {
        this(0);
    }

    public SerialNumber(int lastIssued) {
        if (lastIssued < 0 || lastIssued > 65_535) {
            throw new IllegalArgumentException("lastIssued must be in range 0..65535");
        }
        this.lastIssued = new AtomicInteger(lastIssued);
    }

    public int next() {
        return lastIssued.updateAndGet(current -> current == 65_535 ? 1 : current + 1);
    }
}
