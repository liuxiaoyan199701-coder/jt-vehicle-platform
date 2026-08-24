package io.github.jtplatform.boot.config;

import io.github.jtplatform.api.auth.CachedJwksKeyProvider;
import io.github.jtplatform.api.auth.DisabledStreamRequestAuthenticator;
import io.github.jtplatform.api.auth.JwtStreamRequestAuthenticator;
import io.github.jtplatform.api.auth.Rs256JwtVerifier;
import io.github.jtplatform.api.auth.StreamRequestAuthenticator;
import io.github.jtplatform.api.stream.StreamOpenService;
import io.github.jtplatform.boot.redis.RedisMediaInstanceRegistry;
import io.github.jtplatform.boot.redis.RedisRegistrySupport;
import io.github.jtplatform.boot.redis.RedisStreamRegistry;
import io.github.jtplatform.boot.redis.RedisStreamTokenStore;
import io.github.jtplatform.common.auth.InMemoryStreamTokenStore;
import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.config.DefaultReachableAddressResolver;
import io.github.jtplatform.common.config.ReachableAddressResolver;
import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamTicket;
import io.github.jtplatform.common.port.DeviceRouter;
import io.github.jtplatform.common.port.HttpStreamCommandPort;
import io.github.jtplatform.common.port.InMemoryDeviceRouter;
import io.github.jtplatform.common.port.InMemoryMediaInstanceRegistry;
import io.github.jtplatform.common.port.InMemoryStreamRegistry;
import io.github.jtplatform.common.port.LocalStreamCommandPort;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import io.github.jtplatform.common.port.RecordingCatalog;
import io.github.jtplatform.common.port.StreamCommandHandler;
import io.github.jtplatform.common.port.StreamCommandPort;
import io.github.jtplatform.common.port.StreamNotArrivedListener;
import io.github.jtplatform.common.port.StreamRegistry;
import io.github.jtplatform.common.port.StreamSubscriptionPort;
import io.github.jtplatform.common.service.MediaScheduler;
import io.github.jtplatform.common.service.StreamCoordinator;
import io.github.jtplatform.delivery.diagnostics.StreamNotArrivedEmitter;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
public class CoreRuntimeConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(CoreRuntimeConfiguration.class);

    @Bean
    Clock platformClock() {
        return Clock.systemUTC();
    }

    @Bean
    ReachableAddressResolver reachableAddressResolver() {
        return new DefaultReachableAddressResolver();
    }

    @Bean
    @ConditionalOnMissingBean(StreamRegistry.class)
    StreamRegistry streamRegistry(
            JtPlatformProperties properties,
            Clock clock,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<RedisRegistrySupport> redisSupport) {
        if (properties.getRegistry().getType() == JtPlatformProperties.RegistryType.REDIS) {
            return new RedisStreamRegistry(redisTemplate.getObject(), redisSupport.getObject(), clock,
                    properties.getMedia().getPendingTimeout(), properties.getCluster().getStatePollInterval());
        }
        return new InMemoryStreamRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(MediaInstanceRegistry.class)
    MediaInstanceRegistry mediaInstanceRegistry(
            JtPlatformProperties properties,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<RedisRegistrySupport> redisSupport) {
        if (properties.getRegistry().getType() == JtPlatformProperties.RegistryType.REDIS) {
            return new RedisMediaInstanceRegistry(redisTemplate.getObject(), redisSupport.getObject(),
                    properties.getMedia().getHeartbeatTtl());
        }
        return new InMemoryMediaInstanceRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(DeviceRouter.class)
    DeviceRouter deviceRouter() {
        return new InMemoryDeviceRouter();
    }

    @Bean
    MediaScheduler mediaScheduler(
            MediaInstanceRegistry mediaInstances,
            StreamRegistry streams,
            Clock clock,
            JtPlatformProperties properties) {
        return new MediaScheduler(mediaInstances, streams, clock,
                properties.getMedia().getHeartbeatTtl(), properties.getMedia().getSaturationThreshold());
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService streamLifecycleExecutor() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform()
                .daemon(true)
                .name("stream-lifecycle-", 0)
                .factory());
    }

    @Bean
    @ConditionalOnMissingBean(StreamCommandHandler.class)
    StreamCommandHandler unavailableDeviceCommandHandler() {
        return new StreamCommandHandler() {
            @Override
            public StreamTicket openLive(StreamKey streamKey, MediaTarget target) {
                throw new IllegalStateException("No signal command handler is available");
            }

            @Override
            public StreamTicket openPlayback(
                    StreamKey streamKey,
                    MediaTarget target,
                    LocalDateTime startTime,
                    LocalDateTime endTime) {
                throw new IllegalStateException("No signal command handler is available");
            }

            @Override
            public void close(StreamKey streamKey) {
                throw new IllegalStateException("No signal command handler is available");
            }
        };
    }

    @Bean
    @Profile("standalone")
    StreamCommandPort localStreamCommandPort(StreamCommandHandler handler) {
        return new LocalStreamCommandPort(handler);
    }

    @Bean
    @Profile("cluster")
    StreamCommandPort httpStreamCommandPort(JtPlatformProperties properties) {
        return new HttpStreamCommandPort(properties.getSignal().getCommandBaseUrl());
    }

    /** 无投递器时保持不发事件：诊断是可选增强，不能成为启动前提。 */
    @Bean
    @ConditionalOnMissingBean(StreamNotArrivedListener.class)
    StreamNotArrivedListener streamNotArrivedListener(
            ObjectProvider<MessagePublisher> publishers,
            Clock clock,
            JtPlatformProperties properties) {
        MessagePublisher publisher = publishers.getIfAvailable();
        return publisher == null
                ? StreamNotArrivedListener.NONE
                : new StreamNotArrivedEmitter(
                        publisher, clock, "api-" + properties.getInstance().getNumber());
    }

    @Bean
    StreamCoordinator streamCoordinator(
            StreamRegistry streams,
            MediaScheduler scheduler,
            StreamCommandPort commands,
            ScheduledExecutorService streamLifecycleExecutor,
            Clock clock,
            JtPlatformProperties properties,
            StreamNotArrivedListener notArrivedListener) {
        return new StreamCoordinator(streams, scheduler, commands, streamLifecycleExecutor, clock,
                properties.getMedia().getIdleTimeout(), properties.getMedia().getPendingTimeout(),
                () -> java.util.UUID.randomUUID().toString(), notArrivedListener);
    }

    @Bean
    @Profile("standalone")
    StreamSubscriptionPort localStreamSubscriptionPort(StreamCoordinator coordinator) {
        return coordinator::release;
    }

    @Bean
    @ConditionalOnMissingBean(StreamTokenStore.class)
    StreamTokenStore streamTokenStore(
            JtPlatformProperties properties,
            Clock clock,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<RedisRegistrySupport> redisSupport) {
        if (properties.getRegistry().getType() == JtPlatformProperties.RegistryType.REDIS) {
            return new RedisStreamTokenStore(redisTemplate.getObject(), redisSupport.getObject(),
                    new SecureRandom(), clock);
        }
        return new InMemoryStreamTokenStore();
    }

    @Bean
    StreamRequestAuthenticator streamRequestAuthenticator(JtPlatformProperties properties) {
        var streamAuth = properties.getAuth().getStream();
        if (streamAuth.getMode() == JtPlatformProperties.StreamAuthMode.DISABLED) {
            return new DisabledStreamRequestAuthenticator();
        }
        if (streamAuth.getJwksUri() == null) {
            throw new IllegalStateException("jt.auth.stream.jwks-uri is required when JWT authentication is enabled");
        }
        return new JwtStreamRequestAuthenticator(new Rs256JwtVerifier(
                new CachedJwksKeyProvider(streamAuth.getJwksUri(), streamAuth.getJwksCacheTtl())));
    }

    @Bean
    StreamOpenService streamOpenService(
            StreamRequestAuthenticator authenticator,
            StreamCoordinator coordinator,
            StreamTokenStore tokens,
            ObjectProvider<RecordingCatalog> recordings,
            JtPlatformProperties properties) {
        return new StreamOpenService(authenticator, coordinator, tokens,
                recordings.getIfAvailable(),
                properties.getAuth().getStream().getTokenTtl());
    }

    @Bean
    ApplicationRunner runtimeModeLogger(JtPlatformProperties properties) {
        return arguments -> {
            LOGGER.info("Stream authentication mode: {}", properties.getAuth().getStream().getMode());
            LOGGER.info("Registry type: {}", properties.getRegistry().getType());
        };
    }
}
