package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.DriverConfig;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.signal.AlarmDefinition;
import io.github.jtplatform.simulator.diagnostics.LogEntry;
import io.github.jtplatform.simulator.signal.SignalState;
import io.github.jtplatform.simulator.signal.FleetRuntime;
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

    default SimulatorConfig currentConfig() {
        return SimulatorConfig.defaults();
    }

    MediaViewState mediaState();

    default void setAlarm(AlarmDefinition alarm, boolean enabled) {
    }

    default void clearAlarms() {
    }

    default void setOverspeedKph(double speedKph) {
    }

    default List<FleetRuntime.FleetMemberState> fleetStates() {
        return List.of();
    }

    default void startFleet() {
    }

    default void stopFleet() {
    }

    default void startFleetMember(int index) {
    }

    default void stopFleetMember(int index) {
    }

    default void selectFleetMember(int index) {
    }

    default void enterBlindspot() {
    }

    default void leaveBlindspot() {
    }

    default int blindspotCachedCount() {
        return 0;
    }

    default CompletionStage<Void> sendDriverCard(
            DriverConfig driver, io.github.jtplatform.simulator.signal.SignalClient.DriverAction action) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("driver simulation is not available"));
    }

    List<LogEntry> recentLogs();

    void setListener(RuntimeListener listener);

    @Override
    void close();
}
