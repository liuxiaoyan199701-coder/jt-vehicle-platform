package io.github.jtplatform.boot.cluster;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class ClusterStateHttpClient {
    private static final String ROOT = "internal/cluster-state/";

    private final URI baseUri;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    ClusterStateHttpClient(URI baseUri, Duration connectTimeout, Duration requestTimeout) {
        this.baseUri = normalizeBaseUri(baseUri);
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.client = HttpClient.newBuilder()
                .connectTimeout(requirePositive(connectTimeout, "connectTimeout"))
                .build();
        this.mapper = JsonMapper.builder().build();
    }

    <T> T post(String path, Object body, Class<T> responseType) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(responseType, "responseType");
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (RuntimeException encodingFailure) {
            throw new ClusterStateException("Unable to encode cluster state request", encodingFailure);
        }
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(ROOT + path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ClusterStateException(
                        "Cluster state request failed with HTTP " + response.statusCode() + ": " + response.body());
            }
            return mapper.readValue(response.body(), responseType);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ClusterStateException("Cluster state request was interrupted", interrupted);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ClusterStateException stateFailure) {
                throw stateFailure;
            }
            throw new ClusterStateException("Cluster state request failed", failure);
        }
    }

    private static URI normalizeBaseUri(URI uri) {
        Objects.requireNonNull(uri, "baseUri");
        String value = uri.toASCIIString();
        return URI.create(value.endsWith("/") ? value : value + '/');
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
