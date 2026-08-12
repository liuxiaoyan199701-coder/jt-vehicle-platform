package io.github.jtplatform.delivery.api;

import io.github.jtplatform.delivery.codec.MessageEnvelopeEncoder;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class HttpApiPushTransport implements ApiPushTransport {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final MessageEnvelopeEncoder encoder;
    private final Map<String, String> headers;

    public HttpApiPushTransport(
            HttpClient httpClient,
            URI endpoint,
            Duration requestTimeout,
            MessageEnvelopeEncoder encoder) {
        this(httpClient, endpoint, requestTimeout, encoder, Map.of());
    }

    public HttpApiPushTransport(
            HttpClient httpClient,
            URI endpoint,
            Duration requestTimeout,
            MessageEnvelopeEncoder encoder,
            Map<String, String> headers) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = validateEndpoint(endpoint);
        this.requestTimeout = positive(requestTimeout);
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        LinkedHashMap<String, String> headerCopy = new LinkedHashMap<>();
        Objects.requireNonNull(headers, "headers").forEach((name, value) ->
                headerCopy.put(requireText(name, "header name"), requireText(value, "header value")));
        this.headers = Map.copyOf(headerCopy);
    }

    @Override
    public CompletionStage<Void> push(MessageEnvelope envelope) {
        byte[] body = encoder.encode(Objects.requireNonNull(envelope, "envelope"));
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Idempotency-Key", envelope.eventId());
        return httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new ApiPushException(response.statusCode());
                    }
                    return null;
                });
    }

    private static URI validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
        }
        return endpoint;
    }

    private static Duration positive(Duration value) {
        Objects.requireNonNull(value, "requestTimeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
