package io.github.jtplatform.media.lifecycle;

import io.github.jtplatform.common.config.ReachableAddressResolver;
import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import io.github.jtplatform.common.port.StreamRegistry;
import io.github.jtplatform.common.service.MediaInstanceLifecycle;
import io.github.jtplatform.media.config.MediaRuntimeProperties;
import io.github.jtplatform.media.metrics.MediaNodeLoadMonitor;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class MediaInstanceHeartbeatLifecycle implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaInstanceHeartbeatLifecycle.class);

    private final MediaInstanceRegistry instances;
    private final MediaInstanceLifecycle instanceLifecycle;
    private final MediaNodeLoadMonitor loadMonitor;
    private final MediaRuntimeProperties properties;
    private final ReachableAddressResolver addressResolver;
    private final Clock clock;
    private final BooleanSupplier nodeRunning;
    private final Supplier<MediaPorts> portsSupplier;

    private volatile boolean running;
    private volatile String reachableAddress;
    private volatile MediaPorts ports;
    private ScheduledExecutorService heartbeatExecutor;

    public MediaInstanceHeartbeatLifecycle(
            MediaInstanceRegistry instances,
            StreamRegistry streams,
            MediaNodeLoadMonitor loadMonitor,
            MediaRuntimeProperties properties,
            ReachableAddressResolver addressResolver,
            Clock clock,
            BooleanSupplier nodeRunning,
            Supplier<MediaPorts> portsSupplier) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.loadMonitor = Objects.requireNonNull(loadMonitor, "loadMonitor");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.addressResolver = Objects.requireNonNull(addressResolver, "addressResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nodeRunning = Objects.requireNonNull(nodeRunning, "nodeRunning");
        this.portsSupplier = Objects.requireNonNull(portsSupplier, "portsSupplier");
        this.instanceLifecycle = new MediaInstanceLifecycle(
                instances, Objects.requireNonNull(streams, "streams"), clock, properties.getHeartbeatTtl());
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        properties.validate();
        if (!nodeRunning.getAsBoolean()) {
            throw new IllegalStateException("Media node must be listening before it can register");
        }
        ports = Objects.requireNonNull(portsSupplier.get(), "media ports");
        reachableAddress = addressResolver.resolve(properties.getReachableAddress().toSettings());
        instances.register(newInstance(loadMonitor.sample(), clock.instant()));
        running = true;

        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .daemon(true)
                .name("media-heartbeat-" + properties.getInstanceId())
                .factory());
        long intervalMillis = Math.max(1L, properties.getHeartbeatInterval().toMillis());
        heartbeatExecutor.scheduleWithFixedDelay(
                this::runHeartbeatSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        LOGGER.info("Registered media instance {} at {} with ports {} and capacity {}/{} bit/s",
                properties.getInstanceId(), reachableAddress, ports,
                properties.getCapacity().getMaxStreams(),
                properties.getCapacity().getMaxOutboundBitsPerSecond());
    }

    public void heartbeatNow() {
        if (!running) {
            return;
        }
        if (nodeRunning.getAsBoolean()) {
            var load = loadMonitor.sample();
            Instant heartbeatAt = clock.instant();
            String instanceId = properties.getInstanceId();
            try {
                instances.updateLoad(instanceId, load.currentStreams(), load.outboundBitsPerSecond(), heartbeatAt);
            } catch (IllegalArgumentException missingRegistration) {
                instances.register(newInstance(load, heartbeatAt));
            }
        }
        instanceLifecycle.expireStaleInstances();
    }

    private void runHeartbeatSafely() {
        try {
            heartbeatNow();
        } catch (RuntimeException failure) {
            LOGGER.error("Media instance heartbeat failed for {}", properties.getInstanceId(), failure);
        }
    }

    private MediaInstance newInstance(MediaNodeLoadMonitor.LoadSnapshot load, Instant heartbeatAt) {
        return new MediaInstance(
                properties.getInstanceId(),
                reachableAddress,
                ports,
                properties.getCapacity().getMaxStreams(),
                properties.getCapacity().getMaxOutboundBitsPerSecond(),
                load.currentStreams(),
                load.outboundBitsPerSecond(),
                heartbeatAt,
                false);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
        instances.markDraining(properties.getInstanceId(), clock.instant());
        LOGGER.info("Media instance {} is draining and no longer accepts new stream assignments",
                properties.getInstanceId());
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 50;
    }
}
