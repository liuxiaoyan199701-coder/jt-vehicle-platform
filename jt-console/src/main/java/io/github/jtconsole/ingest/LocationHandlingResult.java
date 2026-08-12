package io.github.jtconsole.ingest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Database projection result returned to the post-commit delivery boundary. */
public record LocationHandlingResult(String outcome, Map<String, Object> liveUpdate) {

    public LocationHandlingResult {
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("outcome must not be blank");
        }
        if (liveUpdate != null) {
            liveUpdate = Collections.unmodifiableMap(new LinkedHashMap<>(liveUpdate));
        }
    }

    public static LocationHandlingResult withoutLiveUpdate(String outcome) {
        return new LocationHandlingResult(outcome, null);
    }
}
