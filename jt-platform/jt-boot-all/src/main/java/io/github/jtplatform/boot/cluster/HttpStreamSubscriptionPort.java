package io.github.jtplatform.boot.cluster;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.port.StreamSubscriptionPort;
import java.util.Objects;

final class HttpStreamSubscriptionPort implements StreamSubscriptionPort {
    private final ClusterStateHttpClient client;

    HttpStreamSubscriptionPort(ClusterStateHttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public int release(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        return client.post(
                        "streams/release",
                        new ClusterStateProtocol.StreamKeyRequest(
                                ClusterStateProtocol.StreamKeyData.from(streamKey)),
                        ClusterStateProtocol.IntResult.class)
                .value();
    }
}
