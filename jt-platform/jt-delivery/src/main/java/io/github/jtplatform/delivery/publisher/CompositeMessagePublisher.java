package io.github.jtplatform.delivery.publisher;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CompositeMessagePublisher implements MessagePublisher {
    private final List<MessagePublisher> publishers;

    public CompositeMessagePublisher(List<? extends MessagePublisher> publishers) {
        Objects.requireNonNull(publishers, "publishers");
        if (publishers.isEmpty()) {
            throw new IllegalArgumentException("publishers must not be empty");
        }
        this.publishers = List.copyOf(publishers);
    }

    @Override
    public PublishResult publish(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        LinkedHashMap<String, PublishDisposition> results = new LinkedHashMap<>();
        for (MessagePublisher publisher : publishers) {
            publisher.publish(envelope).channels().forEach((channel, disposition) -> {
                if (results.putIfAbsent(channel, disposition) != null) {
                    throw new IllegalStateException("duplicate delivery channel: " + channel);
                }
            });
        }
        return new PublishResult(results);
    }

    @Override
    public void close() {
        List<Throwable> failures = new ArrayList<>();
        List<MessagePublisher> reverseOrder = new ArrayList<>(publishers);
        Collections.reverse(reverseOrder);
        for (MessagePublisher publisher : reverseOrder) {
            try {
                publisher.close();
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException combined = new IllegalStateException("failed to close delivery publishers");
            failures.forEach(combined::addSuppressed);
            throw combined;
        }
    }
}
