package io.github.jtplatform.common.auth;

import io.github.jtplatform.common.model.StreamKey;
import java.time.Duration;

public interface StreamTokenStore {
    String issue(StreamKey streamKey, String mediaInstanceId, Duration timeToLive);

    TokenValidationResult validateAndConsume(String token, StreamKey streamKey, String mediaInstanceId);

    int purgeExpired();
}
