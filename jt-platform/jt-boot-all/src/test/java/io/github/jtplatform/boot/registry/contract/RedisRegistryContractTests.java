package io.github.jtplatform.boot.registry.contract;

import io.github.jtplatform.boot.redis.RedisMediaInstanceRegistry;
import io.github.jtplatform.boot.redis.RedisRegistrySupport;
import io.github.jtplatform.boot.redis.RedisStreamRegistry;
import io.github.jtplatform.boot.redis.RedisStreamTokenStore;
import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import io.github.jtplatform.common.port.StreamRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisStreamRegistryContractTest extends StreamRegistryContractTest {
    private final StringRedisTemplate redis = RedisTestSupport.connect();
    private final RedisRegistrySupport support = new RedisRegistrySupport("jt:test:");

    @BeforeEach
    void requireRedis() {
        RedisTestSupport.requireAvailable(redis);
    }

    @Override
    protected StreamRegistry newRegistry() {
        RedisTestSupport.flush(redis);
        return new RedisStreamRegistry(redis, support, Clock.systemUTC(),
                Duration.ofSeconds(30), Duration.ofMillis(20));
    }
}

class RedisMediaInstanceRegistryContractTest extends MediaInstanceRegistryContractTest {
    private final StringRedisTemplate redis = RedisTestSupport.connect();
    private final RedisRegistrySupport support = new RedisRegistrySupport("jt:test:");

    @BeforeEach
    void requireRedis() {
        RedisTestSupport.requireAvailable(redis);
    }

    @Override
    protected MediaInstanceRegistry newRegistry() {
        RedisTestSupport.flush(redis);
        return new RedisMediaInstanceRegistry(redis, support, Duration.ofSeconds(30));
    }
}

class RedisStreamTokenStoreContractTest extends StreamTokenStoreContractTest {
    private final StringRedisTemplate redis = RedisTestSupport.connect();
    private final RedisRegistrySupport support = new RedisRegistrySupport("jt:test:");

    @BeforeEach
    void requireRedis() {
        RedisTestSupport.requireAvailable(redis);
    }

    @Override
    protected StreamTokenStore newStore(Clock clock) {
        RedisTestSupport.flush(redis);
        return new RedisStreamTokenStore(redis, support, new SecureRandom(), clock);
    }
}

final class RedisTestSupport {
    private RedisTestSupport() {
    }

    static StringRedisTemplate connect() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    static void requireAvailable(StringRedisTemplate redis) {
        boolean reachable;
        try {
            reachable = "PONG".equalsIgnoreCase(redis.getConnectionFactory().getConnection().ping());
        } catch (RuntimeException unavailable) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "Redis not reachable at 127.0.0.1:6379; skipping");
    }

    static void flush(StringRedisTemplate redis) {
        Set<String> keys = redis.keys("jt:test:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
