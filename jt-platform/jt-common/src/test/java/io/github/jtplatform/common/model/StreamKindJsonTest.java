package io.github.jtplatform.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StreamKindJsonTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsAndWritesLowercaseWireValues() throws Exception {
        assertEquals(StreamKind.MAIN, objectMapper.readValue("\"main\"", StreamKind.class));
        assertEquals(StreamKind.SUB, objectMapper.readValue("\"sub\"", StreamKind.class));
        assertEquals(StreamKind.PLAYBACK,
                objectMapper.readValue("\"playback\"", StreamKind.class));
        assertEquals(StreamKind.TALKBACK,
                objectMapper.readValue("\"talkback\"", StreamKind.class));
        assertEquals("\"main\"", objectMapper.writeValueAsString(StreamKind.MAIN));
    }
}
