package io.github.jtplatform.simulator.diagnostics;

import io.github.jtplatform.simulator.config.TerminalTime;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class SimulatorLog implements AutoCloseable {
    private static final long DEFAULT_MAX_BYTES = 2L * 1_024 * 1_024;
    private static final int DEFAULT_HISTORY_FILES = 3;
    private static final int MAX_RECENT_ENTRIES = 500;
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(TerminalTime.ZONE);
    private static final Pattern JSON_TOKEN = Pattern.compile(
            "(?i)(\\\"(?:token|authorization)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");
    private static final Pattern PLAIN_TOKEN = Pattern.compile(
            "(?i)(\\b(?:token|authorization|authentication)\\b\\s*[=:]\\s*)([^\\s,;]+)");

    private final Path logFile;
    private final long maxBytes;
    private final int historyFiles;
    private final Clock clock;
    private final CopyOnWriteArrayList<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<LogEntry> recentEntries = new ArrayDeque<>();

    private BufferedWriter writer;
    private boolean closed;

    public SimulatorLog(Path applicationDirectory) throws IOException {
        this(applicationDirectory, DEFAULT_MAX_BYTES, DEFAULT_HISTORY_FILES, Clock.systemUTC());
    }

    SimulatorLog(Path applicationDirectory, long maxBytes, int historyFiles, Clock clock)
            throws IOException {
        Path directory = Objects.requireNonNull(applicationDirectory, "applicationDirectory")
                .toAbsolutePath().normalize();
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (historyFiles < 1) {
            throw new IllegalArgumentException("historyFiles must be positive");
        }
        this.logFile = directory.resolve("simulator.log");
        this.maxBytes = maxBytes;
        this.historyFiles = historyFiles;
        this.clock = Objects.requireNonNull(clock, "clock");
        Files.createDirectories(directory);
        openWriter();
    }

    public void info(String component, String message) {
        append(LogLevel.INFO, component, message, null);
    }

    public void warn(String component, String message) {
        append(LogLevel.WARN, component, message, null);
    }

    public void error(String component, String message, Throwable error) {
        append(LogLevel.ERROR, component, message, error);
    }

    public AutoCloseable addListener(Consumer<LogEntry> listener) {
        Consumer<LogEntry> registered = Objects.requireNonNull(listener, "listener");
        listeners.add(registered);
        return () -> listeners.remove(registered);
    }

    public synchronized List<LogEntry> recentEntries() {
        return List.copyOf(recentEntries);
    }

    public Path logFile() {
        return logFile;
    }

    private void append(LogLevel level, String component, String message, Throwable error) {
        LogEntry entry;
        synchronized (this) {
            if (closed) {
                return;
            }
            String detail = redact(Objects.requireNonNull(message, "message"));
            if (error != null) {
                detail += System.lineSeparator() + redact(stackTrace(error));
            }
            entry = new LogEntry(clock.instant(), level, component, detail);
            recentEntries.addLast(entry);
            while (recentEntries.size() > MAX_RECENT_ENTRIES) {
                recentEntries.removeFirst();
            }
            try {
                String line = format(entry) + System.lineSeparator();
                rotateIfNeeded(line.getBytes(StandardCharsets.UTF_8).length);
                writer.write(line);
                writer.flush();
            } catch (IOException ignored) {
                // UI listeners still receive diagnostics when the local log cannot be written.
            }
        }
        for (Consumer<LogEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException ignored) {
                // One view must not prevent other observers from receiving a log entry.
            }
        }
    }

    private void rotateIfNeeded(int incomingBytes) throws IOException {
        if ((!Files.exists(logFile) || Files.size(logFile) + incomingBytes <= maxBytes)) {
            return;
        }
        writer.close();
        for (int index = historyFiles; index >= 1; index--) {
            Path source = index == 1 ? logFile : rotated(index - 1);
            if (!Files.exists(source)) {
                continue;
            }
            if (index == historyFiles) {
                Files.deleteIfExists(rotated(index));
            }
            Files.move(source, rotated(index), StandardCopyOption.REPLACE_EXISTING);
        }
        openWriter();
    }

    private Path rotated(int index) {
        return logFile.resolveSibling(logFile.getFileName() + "." + index);
    }

    private void openWriter() throws IOException {
        writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String format(LogEntry entry) {
        return "%s %-5s [%s] %s".formatted(
                LINE_TIME.format(entry.timestamp()), entry.level(), entry.component(), entry.message());
    }

    static String redact(String value) {
        String redacted = JSON_TOKEN.matcher(value).replaceAll("$1***$2");
        return PLAIN_TOKEN.matcher(redacted).replaceAll("$1***");
    }

    private static String stackTrace(Throwable error) {
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        return buffer.toString().stripTrailing();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        writer.close();
        listeners.clear();
    }
}
