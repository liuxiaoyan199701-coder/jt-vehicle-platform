package io.github.jtplatform.delivery.rocketmq;

import java.util.Objects;

public record RocketMqQueue(String brokerName, int queueId) implements Comparable<RocketMqQueue> {
    public RocketMqQueue {
        brokerName = Objects.requireNonNull(brokerName, "brokerName").trim();
        if (brokerName.isEmpty()) {
            throw new IllegalArgumentException("brokerName must not be blank");
        }
        if (queueId < 0) {
            throw new IllegalArgumentException("queueId must not be negative");
        }
    }

    @Override
    public int compareTo(RocketMqQueue other) {
        int brokerComparison = brokerName.compareTo(other.brokerName);
        return brokerComparison != 0 ? brokerComparison : Integer.compare(queueId, other.queueId);
    }
}
