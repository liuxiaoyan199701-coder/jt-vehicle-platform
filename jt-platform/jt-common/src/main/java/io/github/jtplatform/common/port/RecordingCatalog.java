package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.RecordingTimeRange;
import io.github.jtplatform.common.model.StreamKey;
import java.io.IOException;
import java.util.List;

public interface RecordingCatalog {
    List<RecordingTimeRange> search(
            StreamKey streamKey, long startTimestampUs, long endTimestampUs) throws IOException;

    List<RecordingTimeRange> searchAll(
            String deviceId, int channel, long startTimestampUs, long endTimestampUs) throws IOException;
}
