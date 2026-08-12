package io.github.jtplatform.media.recording;

public record RecordingSegmentInspection(
        RecordingSegmentStatus status,
        long startTimestampUs,
        long endTimestampUs,
        long frameCount,
        long keyFrameCount,
        String reason) {

    static RecordingSegmentInspection committed(
            long startTimestampUs, long endTimestampUs, long frameCount, long keyFrameCount) {
        return new RecordingSegmentInspection(
                RecordingSegmentStatus.COMMITTED,
                startTimestampUs,
                endTimestampUs,
                frameCount,
                keyFrameCount,
                "");
    }

    static RecordingSegmentInspection incomplete(String reason) {
        return new RecordingSegmentInspection(RecordingSegmentStatus.INCOMPLETE, 0, 0, 0, 0, reason);
    }

    static RecordingSegmentInspection corrupt(String reason) {
        return new RecordingSegmentInspection(RecordingSegmentStatus.CORRUPT, 0, 0, 0, 0, reason);
    }
}
