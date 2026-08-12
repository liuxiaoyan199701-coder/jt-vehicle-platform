package io.github.jtconsole.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jtconsole.live.LiveBroadcaster;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IngestControllerTest {

    private EventIngestionService ingestion;
    private RecentEventLog recentEvents;
    private LiveBroadcaster broadcaster;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ingestion = mock(EventIngestionService.class);
        recentEvents = mock(RecentEventLog.class);
        broadcaster = mock(LiveBroadcaster.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new IngestController(ingestion, recentEvents, broadcaster))
                .build();
    }

    @Test
    void returnsBadRequestWithoutPublishingWhenEnvelopeValidationFails() throws Exception {
        when(ingestion.ingest(any()))
                .thenThrow(new InvalidEnvelopeException("eventId must not be blank"));

        mvc.perform(post("/ingest/jt-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"","deviceId":"00123","messageId":512,"payload":{}}
                                """))
                .andExpect(status().isBadRequest());

        verify(recentEvents).record(any(),
                eq("validation-rejected"),
                eq("invalid-envelope"));
        verify(broadcaster, never()).publish(any());
    }

    @Test
    void recordsAndPublishesOnlyAfterTransactionalServiceReturns() throws Exception {
        Map<String, Object> update = Map.of("deviceId", "00123", "online", true);
        when(ingestion.ingest(any()))
                .thenReturn(new IngestionResult("committed", "located", update));
        when(broadcaster.publish(update)).thenReturn(true);

        mvc.perform(post("/ingest/jt-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"event-1","deviceId":"00123","messageId":512,"payload":{}}
                                """))
                .andExpect(status().isNoContent());

        InOrder order = inOrder(ingestion, recentEvents, broadcaster);
        order.verify(ingestion).ingest(any());
        order.verify(recentEvents).record(any(), eq("committed"), eq("located"));
        order.verify(broadcaster).publish(update);
    }
}
