package io.github.jtplatform.api.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jtplatform.api.auth.DisabledStreamRequestAuthenticator;
import io.github.jtplatform.common.model.RecordingTimeRange;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.port.RecordingCatalog;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecordingSearchControllerTest {
    private static final Instant START = Instant.parse("2026-08-09T12:00:00Z");
    private static final Instant END = Instant.parse("2026-08-09T13:00:00Z");

    @Test
    void returnsAvailableTimeRangesInsteadOfStorageFiles() throws Exception {
        long startUs = 1_786_276_800_000_000L;
        RecordingCatalog catalog = new RecordingCatalog() {
            @Override
            public List<RecordingTimeRange> search(
                    StreamKey streamKey, long queryStartUs, long queryEndUs) {
                assertEquals(new StreamKey("device-1", 2, StreamKind.MAIN), streamKey);
                assertEquals(startUs, queryStartUs);
                return List.of(new RecordingTimeRange(startUs + 1_000_000L, startUs + 2_000_000L));
            }

            @Override
            public List<RecordingTimeRange> searchAll(
                    String deviceId, int channel, long queryStartUs, long queryEndUs) {
                throw new AssertionError("exact stream search expected");
            }
        };
        RecordingSearchController controller = new RecordingSearchController(
                new DisabledStreamRequestAuthenticator(), catalog);

        List<RecordingRangeResponse> response = controller.search(
                null, "device-1", 2, "main", START, END);

        assertEquals(List.of(new RecordingRangeResponse(
                START.plusSeconds(1), START.plusSeconds(2))), response);
    }

    @Test
    void omittedStreamKindSearchesAllSourcesAndCanReturnEmpty() throws Exception {
        RecordingCatalog catalog = new RecordingCatalog() {
            @Override
            public List<RecordingTimeRange> search(
                    StreamKey streamKey, long startTimestampUs, long endTimestampUs) {
                throw new AssertionError("all-source search expected");
            }

            @Override
            public List<RecordingTimeRange> searchAll(
                    String deviceId, int channel, long startTimestampUs, long endTimestampUs) {
                assertEquals("device-1", deviceId);
                assertEquals(2, channel);
                return List.of();
            }
        };
        RecordingSearchController controller = new RecordingSearchController(
                new DisabledStreamRequestAuthenticator(), catalog);

        assertEquals(List.of(), controller.search(
                null, "device-1", 2, null, START, END));
    }

    @Test
    void restEndpointBindsIsoTimestampsAndSerializesRanges() throws Exception {
        long startUs = 1_786_276_800_000_000L;
        RecordingCatalog catalog = new RecordingCatalog() {
            @Override
            public List<RecordingTimeRange> search(
                    StreamKey streamKey, long queryStartUs, long queryEndUs) {
                return List.of(new RecordingTimeRange(startUs, startUs + 1_000_000L));
            }

            @Override
            public List<RecordingTimeRange> searchAll(
                    String deviceId, int channel, long queryStartUs, long queryEndUs) {
                throw new AssertionError("exact stream search expected");
            }
        };
        RecordingSearchController controller = new RecordingSearchController(
                new DisabledStreamRequestAuthenticator(), catalog);
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/recordings/search")
                        .param("deviceId", "device-1")
                        .param("channel", "2")
                        .param("streamKind", "main")
                        .param("startTime", START.toString())
                        .param("endTime", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].startTime").value(START.toString()))
                .andExpect(jsonPath("$[0].endTime").value(START.plusSeconds(1).toString()));
    }
}
