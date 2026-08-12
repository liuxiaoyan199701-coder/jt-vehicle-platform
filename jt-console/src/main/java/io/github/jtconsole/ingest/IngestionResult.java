package io.github.jtconsole.ingest;

import java.util.Map;

/** Result of an event transaction. Any live update is safe to publish after this method returns. */
public record IngestionResult(String result, String outcome, Map<String, Object> liveUpdate) {

    public static IngestionResult committed(LocationHandlingResult handled) {
        return new IngestionResult("committed", handled.outcome(), handled.liveUpdate());
    }

    public static IngestionResult duplicate() {
        return new IngestionResult("duplicate", "duplicate", null);
    }
}
