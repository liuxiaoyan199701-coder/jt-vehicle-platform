package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.StreamKey;

@FunctionalInterface
public interface StreamSubscriptionPort {
    int release(StreamKey streamKey);
}
