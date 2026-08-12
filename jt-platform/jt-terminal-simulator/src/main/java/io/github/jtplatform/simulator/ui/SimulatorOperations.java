package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.diagnostics.LogEntry;
import io.github.jtplatform.simulator.signal.SignalState;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface SimulatorOperations extends AutoCloseable {
    SimulatorConfig loadConfig() throws IOException;

    void saveConfig(SimulatorConfig config) throws IOException;

    CompletionStage<FfmpegProbeResult> detectFfmpeg(String configuredPath);

    void connect(SimulatorConfig config);

    void disconnect();

    CompletionStage<Void> startPreview(SimulatorConfig config);

    CompletionStage<Void> stopPreview();

    SignalState signalState();

    MediaViewState mediaState();

    List<LogEntry> recentLogs();

    void setListener(RuntimeListener listener);

    @Override
    void close();
}
