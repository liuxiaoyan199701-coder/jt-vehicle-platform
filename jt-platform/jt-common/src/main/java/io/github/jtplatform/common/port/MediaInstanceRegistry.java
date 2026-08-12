package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.MediaInstance;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MediaInstanceRegistry {
    void register(MediaInstance instance);

    Optional<MediaInstance> find(String instanceId);

    void updateLoad(String instanceId, int currentStreams, long outboundBitsPerSecond, Instant heartbeatAt);

    void markDraining(String instanceId, Instant heartbeatAt);

    Collection<MediaInstance> activeAfter(Instant heartbeatCutoff);

    List<String> removeExpiredBefore(Instant heartbeatCutoff);

    Collection<MediaInstance> all();
}
