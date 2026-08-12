package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.model.StreamTicket;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class HttpStreamCommandPort implements StreamCommandPort {
    private final URI baseUri;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public HttpStreamCommandPort(URI baseUri) {
        this(baseUri, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                JsonMapper.builder().build(), Duration.ofSeconds(5));
    }

    public HttpStreamCommandPort(URI baseUri, HttpClient client, ObjectMapper mapper, Duration timeout) {
        this.baseUri = normalizeBaseUri(baseUri);
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public StreamTicket openLive(StreamKey streamKey, MediaTarget target) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(target, "target");
        String body = writeJson(new OpenCommand(streamKey.deviceId(), streamKey.channel(),
                streamKey.streamKind().wireValue(), target));
        return readTicket(streamKey, target, send(request("internal/streams/open", body)));
    }

    @Override
    public StreamTicket openPlayback(
            StreamKey streamKey,
            MediaTarget target,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(startTime, "startTime");
        String body = writeJson(new PlaybackCommand(
                streamKey.deviceId(),
                streamKey.channel(),
                streamKey.streamKind().wireValue(),
                target,
                startTime.toString(),
                endTime == null ? null : endTime.toString()));
        return readTicket(streamKey, target, send(request("internal/streams/playback", body)));
    }

    private static StreamTicket readTicket(StreamKey streamKey, MediaTarget target, JsonNode response) {
        return new StreamTicket(
                streamKey,
                requiredText(response, "streamId"),
                target,
                URI.create(requiredText(response, "websocketUri")),
                StreamState.valueOf(requiredText(response, "state")),
                Instant.parse(requiredText(response, "issuedAt")));
    }

    @Override
    public void close(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        send(request("internal/streams/close", writeJson(new CloseCommand(
                streamKey.deviceId(), streamKey.channel(), streamKey.streamKind().wireValue()))));
    }

    private HttpRequest request(String relativePath, String body) {
        return HttpRequest.newBuilder(baseUri.resolve(relativePath))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StreamCommandException("Signal command failed with HTTP " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StreamCommandException("Signal command interrupted", exception);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof StreamCommandException commandException) {
                throw commandException;
            }
            throw new StreamCommandException("Signal command failed", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new StreamCommandException("Unable to encode signal command", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new StreamCommandException("Signal response is missing field: " + field);
        }
        return value.asText();
    }

    private static URI normalizeBaseUri(URI uri) {
        Objects.requireNonNull(uri, "baseUri");
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value : value + '/');
    }

    private record OpenCommand(String deviceId, int channel, String streamKind, MediaTarget target) {}

    private record PlaybackCommand(
            String deviceId,
            int channel,
            String streamKind,
            MediaTarget target,
            String startTime,
            String endTime) {}

    private record CloseCommand(String deviceId, int channel, String streamKind) {}
}
