package io.github.jtplatform.delivery.rocketmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeviceQueueSelectorTest {
    private final DeviceQueueSelector selector = new DeviceQueueSelector();
    private final List<RocketMqQueue> queues = List.of(
            new RocketMqQueue("broker-b", 1),
            new RocketMqQueue("broker-a", 1),
            new RocketMqQueue("broker-a", 0));

    @Test
    void selectionIsStableForADeviceAndIndependentOfInputOrder() {
        RocketMqQueue expected = selector.select("device-1", queues);
        ArrayList<RocketMqQueue> shuffled = new ArrayList<>(queues);
        Collections.reverse(shuffled);

        assertEquals(expected, selector.select("device-1", shuffled));
        assertEquals(expected, selector.select("device-1", List.copyOf(queues)));
    }

    @Test
    void handlesADeviceWhoseHashIsIntegerMinimumValue() {
        String minimumHash = "polygenelubricants";
        assertEquals(Integer.MIN_VALUE, minimumHash.hashCode());
        assertTrue(queues.contains(selector.select(minimumHash, queues)));
    }
}
