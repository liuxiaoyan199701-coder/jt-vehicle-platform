package io.github.jtplatform.common.config;

import java.time.Duration;

public final class PlatformDefaults {
    public static final int INSTANCE_NUMBER = 1;
    public static final String STANDALONE_PROFILE = "standalone";
    public static final String CLUSTER_PROFILE = "cluster";
    public static final Duration PENDING_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration IDLE_STREAM_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration INSTANCE_HEARTBEAT_TTL = Duration.ofSeconds(15);

    private PlatformDefaults() {}
}
