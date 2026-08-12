package io.github.jtplatform.api.recording;

import io.github.jtplatform.api.auth.StreamRequestAuthenticator;
import io.github.jtplatform.common.model.RecordingTimeRange;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.port.RecordingCatalog;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/recordings")
public final class RecordingSearchController {
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private final StreamRequestAuthenticator authenticator;
    private final RecordingCatalog catalog;

    public RecordingSearchController(
            StreamRequestAuthenticator authenticator,
            RecordingCatalog catalog) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @GetMapping("/search")
    public List<RecordingRangeResponse> search(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam String deviceId,
            @RequestParam int channel,
            @RequestParam(required = false) String streamKind,
            @RequestParam Instant startTime,
            @RequestParam Instant endTime) throws IOException {
        authenticator.authenticate(authorization);
        long startTimestampUs = toEpochMicros(startTime, "startTime");
        long endTimestampUs = toEpochMicros(endTime, "endTime");
        if (endTimestampUs < startTimestampUs) {
            throw new IllegalArgumentException("endTime must not precede startTime");
        }

        StreamKind sourceKind = streamKind == null ? null : StreamKind.fromWireValue(streamKind);
        List<RecordingTimeRange> ranges;
        if (sourceKind == null) {
            ranges = catalog.searchAll(
                    deviceId, channel, startTimestampUs, endTimestampUs);
        } else {
            ranges = catalog.search(
                    new StreamKey(deviceId, channel, sourceKind),
                    startTimestampUs,
                    endTimestampUs);
        }
        return ranges.stream()
                .map(range -> new RecordingRangeResponse(
                        fromEpochMicros(range.startTimestampUs()),
                        fromEpochMicros(range.endTimestampUs())))
                .toList();
    }

    private static long toEpochMicros(Instant instant, String name) {
        Objects.requireNonNull(instant, name);
        if (instant.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException(name + " must not precede the Unix epoch");
        }
        try {
            return Math.addExact(
                    Math.multiplyExact(instant.getEpochSecond(), MICROS_PER_SECOND),
                    instant.getNano() / 1_000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " is outside the supported range", overflow);
        }
    }

    private static Instant fromEpochMicros(long timestampUs) {
        long seconds = timestampUs / MICROS_PER_SECOND;
        long micros = timestampUs % MICROS_PER_SECOND;
        return Instant.ofEpochSecond(seconds, micros * 1_000L);
    }
}
