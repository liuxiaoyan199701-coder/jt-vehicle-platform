package io.github.jtplatform.boot.cluster;

import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.auth.TokenValidationResult;
import io.github.jtplatform.common.model.StreamKey;
import java.time.Duration;

final class HttpStreamTokenStore implements StreamTokenStore {
    private final ClusterStateHttpClient client;

    HttpStreamTokenStore(ClusterStateHttpClient client) {
        this.client = client;
    }

    @Override
    public String issue(StreamKey streamKey, String mediaInstanceId, Duration timeToLive) {
        return client.post("tokens/issue", new ClusterStateProtocol.TokenIssueRequest(
                        ClusterStateProtocol.StreamKeyData.from(streamKey), mediaInstanceId, timeToLive.toMillis()),
                        ClusterStateProtocol.StringResult.class)
                .value();
    }

    @Override
    public TokenValidationResult validateAndConsume(
            String token,
            StreamKey streamKey,
            String mediaInstanceId) {
        return client.post("tokens/validate", new ClusterStateProtocol.TokenValidationRequest(
                        token, ClusterStateProtocol.StreamKeyData.from(streamKey), mediaInstanceId),
                        ClusterStateProtocol.TokenValidationResponse.class)
                .toDomain();
    }

    @Override
    public int purgeExpired() {
        return client.post("tokens/purge", ClusterStateProtocol.Ack.ok(),
                        ClusterStateProtocol.IntResult.class)
                .value();
    }
}
