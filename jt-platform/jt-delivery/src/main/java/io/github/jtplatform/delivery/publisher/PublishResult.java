package io.github.jtplatform.delivery.publisher;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PublishResult(Map<String, PublishDisposition> channels) {
    public PublishResult {
        Objects.requireNonNull(channels, "channels");
        LinkedHashMap<String, PublishDisposition> copy = new LinkedHashMap<>();
        channels.forEach((channel, disposition) -> copy.put(requiredChannel(channel),
                Objects.requireNonNull(disposition, "disposition")));
        channels = Collections.unmodifiableMap(copy);
    }

    public static PublishResult of(String channel, PublishDisposition disposition) {
        return new PublishResult(Map.of(requiredChannel(channel), Objects.requireNonNull(disposition, "disposition")));
    }

    public boolean acceptedByEveryEnabledChannel() {
        return channels.values().stream()
                .filter(disposition -> disposition != PublishDisposition.DISABLED)
                .allMatch(disposition -> disposition == PublishDisposition.ACCEPTED);
    }

    private static String requiredChannel(String channel) {
        String normalized = Objects.requireNonNull(channel, "channel").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        return normalized;
    }
}
