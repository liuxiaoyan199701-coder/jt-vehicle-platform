package io.github.jtplatform.signal.admin;

import io.github.jtplatform.signal.config.SignalProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yzh.web.endpoint.MessageManager;

/**
 * Closes device sessions on request from the business console, used when a tenant is suspended
 * or expires.
 *
 * <p>Lives under {@code /internal} like the stream command endpoint, but does not rely on network
 * policy alone: a shared key must match. The key is separate from every other credential in the
 * deployment, because the caller here is a peer process rather than a person.
 *
 * <p>Disconnecting is an accelerator, never the enforcement itself — the device may reconnect at
 * once, and it is device authentication that must then refuse it. Failures are therefore reported
 * plainly and left for the caller to retry rather than escalated.
 */
@RestController
@RequestMapping("/internal/devices")
public class DeviceSessionAdminController {

    public static final String ADMIN_KEY_HEADER = "X-JT-Registry-Key";
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceSessionAdminController.class);
    private static final int MAX_BATCH = 500;

    private final MessageManager messages;
    private final byte[] expectedKey;
    private final boolean enabled;

    public DeviceSessionAdminController(MessageManager messages, SignalProperties properties) {
        this.messages = messages;
        String configured = properties.getAdminKey() == null ? "" : properties.getAdminKey().trim();
        this.enabled = !configured.isEmpty();
        this.expectedKey = configured.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String providedKey,
            @RequestBody DisconnectRequest request) {
        if (!authorized(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<String> clientIds = request == null || request.clientIds() == null
                ? List.of()
                : request.clientIds();
        if (clientIds.size() > MAX_BATCH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "clientIds must not exceed " + MAX_BATCH));
        }

        int disconnected = 0;
        for (String clientId : clientIds) {
            if (clientId != null && !clientId.isBlank() && messages.disconnect(clientId.trim())) {
                disconnected++;
            }
        }
        if (disconnected > 0) {
            LOGGER.info("Disconnected {} device session(s) on request; reason={}",
                    disconnected, request == null ? null : request.reason());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requested", clientIds.size());
        body.put("disconnected", disconnected);
        return ResponseEntity.ok(body);
    }

    private boolean authorized(String providedKey) {
        if (!enabled) {
            // No key configured means the endpoint is not open, not that it is open to everyone.
            return false;
        }
        return providedKey != null
                && MessageDigest.isEqual(providedKey.getBytes(StandardCharsets.UTF_8), expectedKey);
    }

    /** @param reason free-text, logged only, so an operator can tell why sessions dropped */
    public record DisconnectRequest(List<String> clientIds, String reason) {}
}
