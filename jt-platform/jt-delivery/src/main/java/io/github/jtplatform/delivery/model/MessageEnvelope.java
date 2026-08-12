package io.github.jtplatform.delivery.model;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MessageEnvelope(
        String eventId,
        String deviceId,
        long messageId,
        int serialNo,
        String protocolVersion,
        Instant receivedAt,
        String instanceId,
        MessageType type,
        Map<String, Object> payload) {

    public MessageEnvelope {
        eventId = requiredText(eventId, "eventId");
        deviceId = requiredText(deviceId, "deviceId");
        protocolVersion = requiredText(protocolVersion, "protocolVersion");
        instanceId = requiredText(instanceId, "instanceId");
        if (messageId < 0 || messageId > 0xffff_ffffL) {
            throw new IllegalArgumentException("messageId must be in range 0..4294967295");
        }
        if (serialNo < 0 || serialNo > 0xffff) {
            throw new IllegalArgumentException("serialNo must be in range 0..65535");
        }
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(type, "type");
        payload = immutablePayload(payload);
    }

    public static MessageEnvelope create(
            String deviceId,
            long messageId,
            int serialNo,
            String protocolVersion,
            Instant receivedAt,
            String instanceId,
            MessageType type,
            Map<String, Object> payload) {
        return new MessageEnvelope(UUID.randomUUID().toString(), deviceId, messageId, serialNo,
                protocolVersion, receivedAt, instanceId, type, payload);
    }

    private static String requiredText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static Map<String, Object> immutablePayload(Map<String, ?> source) {
        Objects.requireNonNull(source, "payload");
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = requiredText(key, "payload key");
            if (copy.containsKey(normalizedKey)) {
                throw new IllegalArgumentException("duplicate payload key after trimming: " + normalizedKey);
            }
            copy.put(normalizedKey, immutableValue(value, "payload." + normalizedKey));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return value;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException(path + " must be a finite number");
            }
            return number;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException(path + " must be a finite number");
            }
            return number;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof UUID || value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof byte[] || value instanceof ByteBuffer) {
            throw new IllegalArgumentException(path + " must not contain binary data");
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String textKey)) {
                    throw new IllegalArgumentException(path + " must use string object keys");
                }
                String normalizedKey = requiredText(textKey, path + " key");
                if (nested.containsKey(normalizedKey)) {
                    throw new IllegalArgumentException("duplicate payload key after trimming: " + path + '.'
                            + normalizedKey);
                }
                nested.put(normalizedKey, immutableValue(nestedValue, path + '.' + normalizedKey));
            });
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> nested = new ArrayList<>();
            int index = 0;
            for (Object element : iterable) {
                nested.add(immutableValue(element, path + '[' + index++ + ']'));
            }
            return Collections.unmodifiableList(nested);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> nested = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                nested.add(immutableValue(Array.get(value, index), path + '[' + index + ']'));
            }
            return Collections.unmodifiableList(nested);
        }
        throw new IllegalArgumentException(path + " must contain expanded JSON-compatible values, got "
                + value.getClass().getName());
    }
}
