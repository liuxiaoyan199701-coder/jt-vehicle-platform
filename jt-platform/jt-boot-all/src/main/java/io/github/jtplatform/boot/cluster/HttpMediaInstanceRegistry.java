package io.github.jtplatform.boot.cluster;

import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

final class HttpMediaInstanceRegistry implements MediaInstanceRegistry {
    private final ClusterStateHttpClient client;

    HttpMediaInstanceRegistry(ClusterStateHttpClient client) {
        this.client = client;
    }

    @Override
    public void register(MediaInstance instance) {
        client.post("media/register", ClusterStateProtocol.MediaInstanceData.from(instance),
                ClusterStateProtocol.Ack.class);
    }

    @Override
    public Optional<MediaInstance> find(String instanceId) {
        var response = client.post("media/find", new ClusterStateProtocol.InstanceIdRequest(instanceId),
                ClusterStateProtocol.MediaLookupResult.class);
        return Optional.ofNullable(response.instance()).map(ClusterStateProtocol.MediaInstanceData::toDomain);
    }

    @Override
    public void updateLoad(
            String instanceId,
            int currentStreams,
            long outboundBitsPerSecond,
            Instant heartbeatAt) {
        client.post("media/update-load", new ClusterStateProtocol.MediaLoadRequest(
                        instanceId, currentStreams, outboundBitsPerSecond, heartbeatAt.toString()),
                ClusterStateProtocol.Ack.class);
    }

    @Override
    public void markDraining(String instanceId, Instant heartbeatAt) {
        client.post("media/draining",
                new ClusterStateProtocol.MediaHeartbeatRequest(instanceId, heartbeatAt.toString()),
                ClusterStateProtocol.Ack.class);
    }

    @Override
    public Collection<MediaInstance> activeAfter(Instant heartbeatCutoff) {
        return client.post("media/active-after",
                        new ClusterStateProtocol.CutoffRequest(heartbeatCutoff.toString()),
                        ClusterStateProtocol.MediaInstancesResult.class)
                .instances().stream()
                .map(ClusterStateProtocol.MediaInstanceData::toDomain)
                .toList();
    }

    @Override
    public List<String> removeExpiredBefore(Instant heartbeatCutoff) {
        return List.copyOf(client.post("media/remove-expired",
                        new ClusterStateProtocol.CutoffRequest(heartbeatCutoff.toString()),
                        ClusterStateProtocol.StringsResult.class)
                .values());
    }

    @Override
    public Collection<MediaInstance> all() {
        return client.post("media/all", ClusterStateProtocol.Ack.ok(),
                        ClusterStateProtocol.MediaInstancesResult.class)
                .instances().stream()
                .map(ClusterStateProtocol.MediaInstanceData::toDomain)
                .toList();
    }
}
