package io.github.jtplatform.media.recording;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class FfmpegProcessRunner implements RecordingExportProcessRunner {
    private static final long TERMINATION_GRACE_MILLIS = 5_000;

    @Override
    public int run(List<String> command, Path workingDirectory, Duration timeout, Path outputLog)
            throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(outputLog, "outputLog");

        Process process = new ProcessBuilder(List.copyOf(command))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputLog.toFile())
                .start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new IOException("ffmpeg export timed out after " + timeout);
            }
            return process.exitValue();
        } catch (InterruptedException interrupted) {
            terminate(process);
            throw interrupted;
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }
}
