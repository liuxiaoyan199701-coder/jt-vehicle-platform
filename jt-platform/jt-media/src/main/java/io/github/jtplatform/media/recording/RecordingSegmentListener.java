package io.github.jtplatform.media.recording;

@FunctionalInterface
public interface RecordingSegmentListener {
    void onCommitted(RecordingSegmentMetadata metadata);

    static RecordingSegmentListener noOp() {
        return metadata -> {
        };
    }
}
