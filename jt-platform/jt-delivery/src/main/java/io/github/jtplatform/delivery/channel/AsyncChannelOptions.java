package io.github.jtplatform.delivery.channel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public record AsyncChannelOptions(
        int queueCapacity,
        int stripes,
        int criticalReserve,
        int circuitFailureThreshold,
        Duration circuitOpenDuration,
        Duration initialRetryDelay,
        Duration maximumRetryDelay,
        Duration sendTimeout,
        Duration shutdownTimeout,
        Path overflowDirectory) {

    public AsyncChannelOptions(
            int queueCapacity,
            int stripes,
            int criticalReserve,
            int circuitFailureThreshold,
            Duration circuitOpenDuration,
            Duration initialRetryDelay,
            Duration maximumRetryDelay,
            Duration sendTimeout,
            Duration shutdownTimeout) {
        this(queueCapacity, stripes, criticalReserve, circuitFailureThreshold, circuitOpenDuration,
                initialRetryDelay, maximumRetryDelay, sendTimeout, shutdownTimeout,
                Path.of(System.getProperty("java.io.tmpdir"), "jt-platform-delivery-overflow",
                        Long.toString(ProcessHandle.current().pid())));
    }

    public static AsyncChannelOptions defaults() {
        return new AsyncChannelOptions(1024, 4, 64, 5, Duration.ofSeconds(30),
                Duration.ofMillis(200), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(5));
    }

    public AsyncChannelOptions {
        if (queueCapacity < 2) {
            throw new IllegalArgumentException("queueCapacity must be at least 2");
        }
        if (stripes < 1 || stripes > queueCapacity) {
            throw new IllegalArgumentException("stripes must be in range 1..queueCapacity");
        }
        if (criticalReserve < 1 || criticalReserve > queueCapacity) {
            throw new IllegalArgumentException("criticalReserve must be in range 1..queueCapacity");
        }
        if (circuitFailureThreshold < 1) {
            throw new IllegalArgumentException("circuitFailureThreshold must be positive");
        }
        requirePositive(circuitOpenDuration, "circuitOpenDuration");
        requirePositive(initialRetryDelay, "initialRetryDelay");
        requirePositive(maximumRetryDelay, "maximumRetryDelay");
        requirePositive(sendTimeout, "sendTimeout");
        requirePositive(shutdownTimeout, "shutdownTimeout");
        Objects.requireNonNull(overflowDirectory, "overflowDirectory");
        if (maximumRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException("maximumRetryDelay must not be shorter than initialRetryDelay");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
