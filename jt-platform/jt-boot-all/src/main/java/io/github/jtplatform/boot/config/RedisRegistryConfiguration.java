package io.github.jtplatform.boot.config;

import io.github.jtplatform.boot.redis.RedisRegistrySupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jt.registry", name = "type", havingValue = "redis")
public class RedisRegistryConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRegistryConfiguration.class);

    @Bean
    LettuceConnectionFactory registryRedisConnectionFactory(JtPlatformProperties properties) {
        JtPlatformProperties.Redis redis = properties.getRegistry().getRedis();
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            configuration.setPassword(RedisPassword.of(redis.getPassword()));
        }
        configuration.setDatabase(redis.getDatabase());
        return new LettuceConnectionFactory(configuration);
    }

    @Bean
    StringRedisTemplate registryRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    RedisRegistrySupport redisRegistrySupport(JtPlatformProperties properties) {
        return new RedisRegistrySupport(properties.getRegistry().getRedis().getKeyPrefix());
    }

    @Bean
    ApplicationRunner redisConnectivityProbe(StringRedisTemplate redisTemplate) {
        return args -> {
            try {
                String pong = redisTemplate.getConnectionFactory().getConnection().ping();
                if (!"PONG".equalsIgnoreCase(pong)) {
                    throw new IllegalStateException("Redis ping returned unexpected response: " + pong);
                }
                LOGGER.info("Redis registry connectivity verified");
            } catch (RuntimeException failure) {
                throw new IllegalStateException(
                        "jt.registry.type=redis but Redis is unreachable; refusing to start with a silently degraded registry",
                        failure);
            }
        };
    }
}
