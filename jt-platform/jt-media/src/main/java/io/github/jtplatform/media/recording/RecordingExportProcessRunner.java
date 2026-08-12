package io.github.jtplatform.media.recording;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
interface RecordingExportProcessRunner {
    int run(List<String> command, Path workingDirectory, Duration timeout, Path outputLog)
            throws IOException, InterruptedException;
}
