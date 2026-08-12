package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public interface StreamRegistry {
    Registration getOrRegister(StreamKey streamKey, Supplier<StreamEntry> factory);

    Optional<StreamEntry> find(StreamKey streamKey);

    boolean markLive(StreamKey streamKey);

    boolean markDead(StreamKey streamKey, String reason);

    int addSubscriber(StreamKey streamKey);

    int removeSubscriber(StreamKey streamKey);

    List<StreamKey> expirePendingBefore(Instant cutoff, String reason);

    int invalidateMediaInstance(String instanceId, String reason);

    Collection<StreamEntry> entries();

    record Registration(StreamEntry entry, boolean created) {}
}
