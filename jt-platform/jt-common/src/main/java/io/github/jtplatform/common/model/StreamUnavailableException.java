package io.github.jtplatform.common.model;

public final class StreamUnavailableException extends RuntimeException {
    private final StreamKey streamKey;

    public StreamUnavailableException(StreamKey streamKey, String message) {
        super(message);
        this.streamKey = streamKey;
    }

    public StreamKey streamKey() {
        return streamKey;
    }
}
