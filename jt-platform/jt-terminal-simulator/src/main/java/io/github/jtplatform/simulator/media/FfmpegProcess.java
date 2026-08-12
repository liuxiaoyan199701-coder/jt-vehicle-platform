package io.github.jtplatform.simulator.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class FfmpegProcess implements AutoCloseable {
    private static final Duration GRACEFUL_STOP_TIMEOUT = Duration.ofSeconds(2);

    private final Process process;
    private final Thread stderrReader;
    private final CompletableFuture<Integer> exit;

    private FfmpegProcess(Process process, Consumer<String> diagnostics) {
        this.process = process;
        this.stderrReader = Thread.ofVirtual().name("ffmpeg-stderr-" + process.pid()).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        diagnostics.accept(line);
                    } catch (RuntimeException ignored) {
                        // Diagnostic consumers must never stop draining FFmpeg stderr.
                    }
                }
            } catch (IOException failure) {
                if (process.isAlive()) {
                    try {
                        diagnostics.accept("Failed to read FFmpeg diagnostics: " + failure.getMessage());
                    } catch (RuntimeException ignored) {
                        // The process lifecycle remains authoritative.
                    }
                }
            }
        });
        this.exit = process.onExit().thenApply(Process::exitValue);
    }

    public static FfmpegProcess start(List<String> command, Consumer<String> diagnostics) throws IOException {
        List<String> checkedCommand = List.copyOf(command);
        if (checkedCommand.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Consumer<String> checkedDiagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        Process process = new ProcessBuilder(checkedCommand)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        return new FfmpegProcess(process, checkedDiagnostics);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    public CompletableFuture<Integer> exit() {
        return exit;
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(GRACEFUL_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        try {
            stderrReader.join(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
