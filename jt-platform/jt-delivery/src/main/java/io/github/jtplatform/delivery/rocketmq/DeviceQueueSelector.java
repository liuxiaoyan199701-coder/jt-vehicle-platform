package io.github.jtplatform.delivery.rocketmq;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class DeviceQueueSelector {
    public RocketMqQueue select(String deviceId, List<RocketMqQueue> availableQueues) {
        String key = Objects.requireNonNull(deviceId, "deviceId").trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        Objects.requireNonNull(availableQueues, "availableQueues");
        if (availableQueues.isEmpty()) {
            throw new IllegalArgumentException("availableQueues must not be empty");
        }
        ArrayList<RocketMqQueue> stableOrder = new ArrayList<>(availableQueues);
        if (stableOrder.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("availableQueues must not contain null");
        }
        if (new HashSet<>(stableOrder).size() != stableOrder.size()) {
            throw new IllegalArgumentException("availableQueues must not contain duplicates");
        }
        stableOrder.sort(RocketMqQueue::compareTo);
        return stableOrder.get(Math.floorMod(key.hashCode(), stableOrder.size()));
    }
}
