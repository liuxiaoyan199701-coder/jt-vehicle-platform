package io.github.jtplatform.signal.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class HttpRemoteDeviceInformationClient implements RemoteDeviceInformationClient {
    private static final String[] DECISION_FIELDS = {"allowed", "authorized", "accessAllowed", "decision"};

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final ObjectMapper mapper;
    private final Map<String, String> headers;

    public HttpRemoteDeviceInformationClient(
            HttpClient httpClient,
            URI endpoint,
            Duration requestTimeout,
            Map<String, String> headers) {
        this(httpClient, endpoint, requestTimeout,
                JsonMapper.builder()
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build(),
                headers);
    }

    HttpRemoteDeviceInformationClient(
            HttpClient httpClient,
            URI endpoint,
            Duration requestTimeout,
            ObjectMapper mapper,
            Map<String, String> headers) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = validateEndpoint(endpoint);
        this.requestTimeout = positive(requestTimeout, "jt.auth.device.remote.request-timeout");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> copy.put(requireText(name, "header name"),
                    requireText(value, "header value")));
        }
        this.headers = Map.copyOf(copy);
    }

    @Override
    public Optional<DeviceInformation> find(String terminalId) {
        String normalized = requireText(terminalId, "terminalId");
        URI requestUri = UriComponentsBuilder.fromUri(endpoint)
                .queryParam("terminalId", normalized)
                .build()
                .encode()
                .toUri();
        HttpRequest.Builder request = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET();
        headers.forEach(request::header);

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeviceInformationUnavailableException("remote device query was interrupted", exception);
        } catch (IOException | RuntimeException exception) {
            throw new DeviceInformationUnavailableException("remote device query failed", exception);
        }

        if (response.statusCode() == 404 || response.statusCode() == 204) {
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DeviceInformationUnavailableException(
                    "remote device query returned HTTP " + response.statusCode());
        }
        return Optional.of(decode(response.body()));
    }

    private DeviceInformation decode(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new DeviceInformationUnavailableException("remote device response must be a JSON object");
            }
            for (String field : DECISION_FIELDS) {
                if (root.has(field) || root.has(field.toLowerCase(Locale.ROOT))) {
                    throw new DeviceInformationUnavailableException(
                            "remote device response must contain device information only, not an access decision");
                }
            }
            RemoteDeviceResponse response = mapper.treeToValue(root, RemoteDeviceResponse.class);
            return new DeviceInformation(
                    response.terminalId(),
                    response.deviceId(),
                    response.mobileNo(),
                    response.plateNo(),
                    response.tenantCode(),
                    response.tenantActive());
        } catch (DeviceInformationUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DeviceInformationUnavailableException("remote device response is invalid", exception);
        }
    }

    private static URI validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "jt.auth.device.remote.endpoint");
        if (!endpoint.isAbsolute()
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException(
                    "jt.auth.device.remote.endpoint must be an absolute HTTP(S) URI");
        }
        return endpoint;
    }

    static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    /**
     * {@code tenantActive} is a fact about the owning tenant, not an access decision: this module
     * still decides what an inactive tenant means. It stays boxed so that a source which does not
     * report tenancy is distinguishable from one reporting "inactive".
     */
    private record RemoteDeviceResponse(
            String terminalId,
            String deviceId,
            String mobileNo,
            String plateNo,
            String tenantCode,
            Boolean tenantActive) {
    }
}
