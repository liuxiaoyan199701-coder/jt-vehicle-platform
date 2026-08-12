package io.github.jtplatform.api.stream;

import io.github.jtplatform.api.auth.StreamRequestAuthenticator;
import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.model.StreamTicket;
import io.github.jtplatform.common.port.RecordingCatalog;
import io.github.jtplatform.common.service.StreamCoordinator;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public final class StreamOpenService {
    private final StreamRequestAuthenticator authenticator;
    private final StreamCoordinator coordinator;
    private final StreamTokenStore tokens;
    private final RecordingCatalog recordings;
    private final Duration tokenTtl;

    public StreamOpenService(
            StreamRequestAuthenticator authenticator,
            StreamCoordinator coordinator,
            StreamTokenStore tokens,
            Duration tokenTtl) {
        this(authenticator, coordinator, tokens, null, tokenTtl);
    }

    public StreamOpenService(
            StreamRequestAuthenticator authenticator,
            StreamCoordinator coordinator,
            StreamTokenStore tokens,
            RecordingCatalog recordings,
            Duration tokenTtl) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.recordings = recordings;
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
    }

    public OpenStreamResponse open(String authorizationHeader, OpenStreamRequest request) {
        Objects.requireNonNull(request, "request");
        authenticator.authenticate(authorizationHeader);
        StreamTicket ticket;
        if (request.streamKind() == StreamKind.PLAYBACK) {
            ticket = hasLocalRecording(request)
                    ? coordinator.openLocalPlayback(request.streamKey())
                    : coordinator.openPlayback(
                            request.streamKey(),
                            LocalDateTime.ofInstant(request.startTime(), ZoneOffset.UTC),
                            LocalDateTime.ofInstant(request.endTime(), ZoneOffset.UTC));
        } else {
            ticket = coordinator.open(request.streamKey());
        }
        if (ticket.state() == StreamState.DEAD) {
            throw new IllegalStateException("Stream became unavailable while opening");
        }
        String token = tokens.issue(ticket.streamKey(), ticket.target().instanceId(), tokenTtl);
        String state = ticket.state() == StreamState.LIVE ? "live" : "waking";
        return new OpenStreamResponse(ticket.streamId(), ticket.target().instanceId(),
                subscriptionUri(ticket, request), token, state);
    }

    private static String subscriptionUri(StreamTicket ticket, OpenStreamRequest request) {
        StreamKey streamKey = ticket.streamKey();
        String base = ticket.websocketUri().toASCIIString();
        String separator = base.contains("?") ? "&" : "?";
        String uri = base + separator
                + "deviceId=" + encode(streamKey.deviceId())
                + "&channel=" + streamKey.channel()
                + "&streamKind=" + encode(streamKey.streamKind().wireValue());
        if (request.streamKind() == StreamKind.PLAYBACK) {
            uri += "&startTime=" + encode(request.startTime().toString())
                    + "&endTime=" + encode(request.endTime().toString());
        }
        return uri;
    }

    private boolean hasLocalRecording(OpenStreamRequest request) {
        if (recordings == null) {
            return false;
        }
        try {
            return !recordings.searchAll(
                    request.deviceId(),
                    request.channel(),
                    toEpochMicros(request.startTime()),
                    toEpochMicros(request.endTime())).isEmpty();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to search local recordings", failure);
        }
    }

    private static long toEpochMicros(java.time.Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
