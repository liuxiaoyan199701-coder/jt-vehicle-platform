package io.github.jtplatform.boot.redis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 注册表的键命名空间与 JSON 序列化工具。所有键共享一个可配置前缀，
 * 避免多套部署共用同一个 Redis 时互相冲突。
 */
public final class RedisRegistrySupport {
    private final String keyPrefix;
    private final ObjectMapper mapper;

    public RedisRegistrySupport(String keyPrefix) {
        this.keyPrefix = requirePrefix(keyPrefix);
        this.mapper = JsonMapper.builder().build();
    }

    public String streamKey(String externalId) {
        return keyPrefix + "stream:" + externalId;
    }

    public String streamIndexKey() {
        return keyPrefix + "stream:index";
    }

    public String instanceKey(String instanceId) {
        return keyPrefix + "instance:" + instanceId;
    }

    public String instanceIndexKey() {
        return keyPrefix + "instance:index";
    }

    public String tokenKey(String token) {
        return keyPrefix + "token:" + token;
    }

    public String tokenIndexKey() {
        return keyPrefix + "token:index";
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new RedisRegistryException("Unable to serialize registry value", failure);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException failure) {
            throw new RedisRegistryException("Unable to deserialize registry value", failure);
        }
    }

    public RedisScript<Long> longScript(String resource) {
        return new DefaultRedisScript<>(loadLua(resource), Long.class);
    }

    public RedisScript<String> stringScript(String resource) {
        return new DefaultRedisScript<>(loadLua(resource), String.class);
    }

    private static String loadLua(String resource) {
        try (InputStream input = RedisRegistrySupport.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing Lua resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read Lua resource: " + resource, failure);
        }
    }

    private static String requirePrefix(String value) {
        String result = Objects.requireNonNull(value, "keyPrefix").trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        return result;
    }
}
