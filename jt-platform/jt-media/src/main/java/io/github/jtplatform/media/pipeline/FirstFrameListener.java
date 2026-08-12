package io.github.jtplatform.media.pipeline;

import io.github.jtplatform.common.model.StreamKey;

@FunctionalInterface
public interface FirstFrameListener {
    /**
     * @return {@code true} when the ingress is allowed to enter the sink pipeline
     */
    boolean onFirstFrame(StreamKey streamKey);

    static FirstFrameListener noOp() {
        return ignored -> true;
    }
}
