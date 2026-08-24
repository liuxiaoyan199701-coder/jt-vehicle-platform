package io.github.jtplatform.delivery.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageTypeTest {

    /**
     * wireValue 是跨进程契约：控制台按这个字符串分支路由，改名等于让日志静默消失。
     */
    @Test
    void deviceLogTravelsAsDeviceLogAndIsNotCritical() {
        assertThat(MessageType.DEVICE_LOG.wireValue()).isEqualTo("device_log");
        assertThat(MessageType.DEVICE_LOG.reliability())
                .isEqualTo(DeliveryReliability.BEST_EFFORT);
        assertThat(MessageType.DEVICE_LOG.isCritical()).isFalse();
    }

    @Test
    void criticalTypesStayCritical() {
        assertThat(MessageType.CONNECTION.isCritical()).isTrue();
        assertThat(MessageType.LOCATION.isCritical()).isFalse();
    }
}
