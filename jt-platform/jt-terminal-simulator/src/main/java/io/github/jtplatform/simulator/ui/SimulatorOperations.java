package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.diagnostics.LogEntry;
import io.github.jtplatform.simulator.signal.SignalState;
import io.github.jtplatform.simulator.trip.TripViewState;
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

    /** 开始模拟行程。未建立会话时无效——行程唯一的副作用是通过会话上报位置。 */
    void startTrip();

    /** 停止模拟行程。里程与进度保留，再次开始时从原处继续。 */
    void stopTrip();

    TripViewState tripState();

    SignalState signalState();

    MediaViewState mediaState();

    List<LogEntry> recentLogs();

    void setListener(RuntimeListener listener);

    @Override
    void close();
}
