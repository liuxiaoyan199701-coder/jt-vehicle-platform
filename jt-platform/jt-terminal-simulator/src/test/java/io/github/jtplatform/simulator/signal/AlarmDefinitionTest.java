package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AlarmDefinitionTest {
    @Test
    void commonAlarmBitsMatchTheT0200Definition() {
        assertEquals(5, AlarmDefinition.COMMON.size());
        assertEquals(1, AlarmDefinition.COMMON.get(0).mask());
        assertEquals(1 << 1, AlarmDefinition.COMMON.get(1).mask());
        assertEquals(1 << 2, AlarmDefinition.COMMON.get(2).mask());
        assertEquals(1 << 6, AlarmDefinition.COMMON.get(3).mask());
        assertEquals(1 << 7, AlarmDefinition.COMMON.get(4).mask());
        assertTrue(AlarmDefinition.COMMON.stream().allMatch(alarm -> alarm.name().contains("bit")));
    }
}
