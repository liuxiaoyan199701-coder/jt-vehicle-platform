package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.TerminalTime;
import io.github.jtplatform.simulator.diagnostics.LogEntry;
import io.github.jtplatform.simulator.signal.SignalState;
import io.github.jtplatform.simulator.trip.TripViewState;
import io.github.jtplatform.simulator.config.DriverConfig;
import io.github.jtplatform.simulator.signal.AlarmDefinition;
import io.github.jtplatform.simulator.signal.SignalClient;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

public final class SimulatorView extends BorderPane implements RuntimeListener, AutoCloseable {
    private static final int MAX_VISIBLE_LOGS = 500;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS")
            .withZone(TerminalTime.ZONE);

    private final SimulatorOperations operations;
    private final SimulatorConfig initialConfig;
    private final ConfigurationPane configuration;
    private final Button saveButton = new Button("保存配置");
    private final Button connectButton = new Button("连接");
    private final Button previewButton = new Button("启动预览");
    private final Label signalState = new Label("808 未连接");
    private final Label ffmpegState = new Label("FFmpeg 未检测");
    private final Label tripState = new Label("行程 未连接");
    private final Label deviceState = new Label("设备 -");
    private final Label lastReportState = new Label("最近上报 -");
    private final Label streamState = new Label("推流 空闲");
    private final Label activity = new Label("就绪");
    private final ImageView previewImage = new ImageView();
    private final Label previewPlaceholder = new Label("暂无预览画面");
    private final ObservableList<LogEntry> logs = FXCollections.observableArrayList();
    private final ListView<LogEntry> logView = new ListView<>(logs);
    private final Label mediaStateValue = metricValue();
    private final Label targetValue = metricValue();
    private final Label streamValue = metricValue();
    private final Label tracksValue = metricValue();
    private final Label fpsValue = metricValue();
    private final Label bitrateValue = metricValue();
    private final Label queueValue = metricValue();
    private final Label droppedValue = metricValue();
    private final Timeline statisticsTimer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile SignalState currentSignalState = SignalState.DISCONNECTED;
    private final List<CheckBox> alarmChecks = new java.util.ArrayList<>();
    private final TextField overspeedField = new TextField("80");
    private final TextField driverName = new TextField();
    private final TextField driverIdCard = new TextField();
    private final TextField driverLicenseNo = new TextField();
    private final TextField driverInstitution = new TextField();
    private final TextField driverValidPeriod = new TextField();
    private volatile MediaViewState currentMediaState = MediaViewState.idle();
    private boolean ffmpegSupported;
    private boolean previewActionRunning;

    public SimulatorView(SimulatorOperations operations, SimulatorConfig initialConfig) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.initialConfig = Objects.requireNonNull(initialConfig, "initialConfig");
        this.configuration = new ConfigurationPane(this.initialConfig);
        setId("simulator-root");
        getStyleClass().add("simulator-root");
        setMinSize(960, 640);

        saveButton.setId("save-config");
        saveButton.getStyleClass().add("secondary-button");
        connectButton.setId("connect-toggle");
        connectButton.getStyleClass().add("primary-button");
        previewButton.setId("preview-toggle");
        previewButton.getStyleClass().add("primary-button");
        signalState.setId("signal-state");
        signalState.getStyleClass().addAll("state-pill", "state-offline");
        signalState.setMinWidth(118);
        signalState.setPrefWidth(138);
        signalState.setMaxWidth(168);
        signalState.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        ffmpegState.setId("ffmpeg-state");
        ffmpegState.getStyleClass().add("ffmpeg-status");
        tripState.setId("trip-state");
        tripState.getStyleClass().add("trip-status");
        ffmpegState.setMinWidth(170);
        ffmpegState.setPrefWidth(300);
        ffmpegState.setMaxWidth(390);
        ffmpegState.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        activity.setId("activity-state");
        activity.getStyleClass().add("activity-label");
        activity.setMinWidth(0);
        activity.setMaxWidth(Double.MAX_VALUE);
        activity.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        Tooltip activityTooltip = new Tooltip();
        activityTooltip.textProperty().bind(activity.textProperty());
        activity.setTooltip(activityTooltip);

        deviceState.getStyleClass().add("status-item");
        lastReportState.getStyleClass().add("status-item");
        streamState.getStyleClass().add("status-item");
        DriverConfig rememberedDriver = initialConfig.driver();
        driverName.setText(rememberedDriver.name());
        driverIdCard.setText(rememberedDriver.idCard());
        driverLicenseNo.setText(rememberedDriver.licenseNo());
        driverInstitution.setText(rememberedDriver.institution());
        driverValidPeriod.setText(rememberedDriver.licenseValidPeriod());
        overspeedField.setText(Integer.toString(initialConfig.alarm().overspeedKph()));
        setTop(createHeader());
        setCenter(createWorkspace());
        setBottom(createStatusBar());

        saveButton.setOnAction(event -> saveConfiguration(true));
        connectButton.setOnAction(event -> toggleConnection());
        configuration.tripToggleButton().setOnAction(event -> toggleTrip());
        previewButton.setOnAction(event -> togglePreview());
        configuration.browseFfmpegButton().setOnAction(event -> browseFfmpeg());
        configuration.detectFfmpegButton().setOnAction(event -> detectFfmpeg());
        configuration.refreshDevicesButton().setOnAction(event -> detectFfmpeg());

        operations.setListener(this);
        logs.setAll(operations.recentLogs());
        trimLogs();
        updateSignalState(operations.signalState(), "");
        updateMediaState(operations.mediaState());

        statisticsTimer = new Timeline(new KeyFrame(Duration.millis(500), event -> {
            if (!closed.get()) {
                updateMediaState(operations.mediaState());
            }
        }));
        statisticsTimer.setCycleCount(Timeline.INDEFINITE);

        mediaStateValue.setId("media-state");
        targetValue.setId("target-node");
        streamValue.setId("stream-kind");
        tracksValue.setId("media-tracks");
        fpsValue.setId("media-fps");
        bitrateValue.setId("media-bitrate");
        queueValue.setId("queue-depth");
        droppedValue.setId("dropped-frames");
    }

    public void activate() {
        statisticsTimer.play();
        detectFfmpeg();
    }

    private Node createHeader() {
        Label title = new Label("JT Platform 终端模拟器");
        title.getStyleClass().add("app-title");
        Label protocol = new Label("JT/T 808 · JT/T 1078");
        protocol.getStyleClass().add("app-subtitle");
        VBox identity = new VBox(1, title, protocol);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(
                12, identity, new Separator(Orientation.VERTICAL), signalState,
                spacer, saveButton, connectButton);
        header.getStyleClass().add("app-header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Node createWorkspace() {
        SplitPane workspace = new SplitPane();
        workspace.setOrientation(Orientation.HORIZONTAL);
        TitledPane configurationSection = new TitledPane("连接与设备配置", configuration);
        configurationSection.setExpanded(true);
        configurationSection.setCollapsible(true);
        configurationSection.getStyleClass().add("configuration-section");
        workspace.getItems().setAll(configurationSection, createOperationsPane());
        workspace.setDividerPositions(0.36);
        workspace.getStyleClass().add("workspace-split");
        return workspace;
    }

    private Node createOperationsPane() {
        VBox previewPanel = new VBox(10);
        previewPanel.getStyleClass().add("preview-panel");
        previewPanel.setPadding(new Insets(14, 16, 12, 16));
        Label heading = new Label("摄像头预览");
        heading.getStyleClass().add("panel-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox previewHeader = new HBox(10, heading, spacer, previewButton);
        previewHeader.setAlignment(Pos.CENTER_LEFT);

        StackPane surface = new StackPane(previewImage, previewPlaceholder);
        surface.setId("preview-surface");
        surface.getStyleClass().add("preview-surface");
        surface.setMinHeight(250);
        surface.setPrefHeight(430);
        VBox.setVgrow(surface, Priority.ALWAYS);
        previewImage.setId("preview-image");
        previewImage.setPreserveRatio(true);
        previewImage.setSmooth(true);
        previewImage.fitWidthProperty().bind(
                Bindings.max(1, surface.widthProperty().subtract(24)));
        previewImage.fitHeightProperty().bind(
                Bindings.max(1, surface.heightProperty().subtract(24)));
        previewPlaceholder.getStyleClass().add("preview-placeholder");
        previewPlaceholder.visibleProperty().bind(previewImage.imageProperty().isNull());
        previewPanel.getChildren().addAll(previewHeader, surface, createMetrics());

        VBox logPanel = new VBox(8);
        logPanel.getStyleClass().add("log-panel");
        logPanel.setPadding(new Insets(10, 16, 12, 16));
        Label logTitle = new Label("运行日志");
        logTitle.getStyleClass().add("panel-title");
        logView.setId("runtime-log");
        logView.setPlaceholder(new Label("暂无日志"));
        logView.setCellFactory(ignored -> new LogCell());
        VBox.setVgrow(logView, Priority.ALWAYS);
        logPanel.getChildren().setAll(logTitle, logView);

        TabPane tabs = new TabPane();
        VBox tripPanel = new VBox(12, panelTitle("行程模拟"), tripState,
                new Label("行程位置会按配置中的路线和上报间隔发送。"));
        tripPanel.setPadding(new Insets(16));
        Button tripAction = new Button("切换行程");
        tripAction.setOnAction(event -> toggleTrip());
        tripPanel.getChildren().add(tripAction);
        tabs.getTabs().setAll(
                tab("行程", tripPanel),
                tab("视频推流", previewPanel),
                tab("拍照", new VBox(12, panelTitle("拍照"), new Label("等待平台下发 0x8801 拍照命令。"))),
                tab("告警模拟", createAlarmPanel()),
                tab("驾驶员", createDriverPanel()),
                tab("日志", logPanel));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("operations-tabs");
        return tabs;
    }

    private Node createAlarmPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(16));
        panel.getStyleClass().add("control-panel");
        panel.getChildren().add(panelTitle("0x0200 报警位模拟"));
        for (AlarmDefinition definition : AlarmDefinition.COMMON) {
            CheckBox check = new CheckBox(definition.name());
            check.setId("alarm-bit-" + definition.bit());
            check.setSelected((initialConfig.alarm().warnBits() & definition.mask()) != 0);
            check.selectedProperty().addListener((observable, oldValue, enabled) ->
                    operations.setAlarm(definition, enabled));
            alarmChecks.add(check);
            panel.getChildren().add(check);
        }
        HBox speed = new HBox(8, new Label("超速联动速度 (km/h)"), overspeedField);
        overspeedField.setId("overspeed-kph");
        overspeedField.setPrefColumnCount(6);
        Button applySpeed = new Button("应用");
        applySpeed.setOnAction(event -> {
            try {
                operations.setOverspeedKph(Double.parseDouble(overspeedField.getText().trim()));
                activity.setText("超速联动速度已更新");
            } catch (RuntimeException failure) {
                activity.setText("超速速度无效：请输入 1～300");
            }
        });
        speed.getChildren().add(applySpeed);
        Button clear = new Button("全部解除");
        clear.setId("clear-alarms");
        clear.setOnAction(event -> {
            operations.clearAlarms();
            alarmChecks.forEach(check -> check.setSelected(false));
            activity.setText("全部报警位已解除");
        });
        panel.getChildren().addAll(speed, clear,
                new Label("置位后会随每次位置上报持续携带，平台负责生成与去重告警。"));
        return panel;
    }

    private Node createDriverPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));
        panel.getStyleClass().add("control-panel");
        panel.getChildren().add(panelTitle("驾驶员身份识别（0702）"));
        driverName.setId("driver-name");
        driverIdCard.setId("driver-id-card");
        driverLicenseNo.setId("driver-license-no");
        driverInstitution.setId("driver-institution");
        driverValidPeriod.setId("driver-valid-period");
        panel.getChildren().addAll(
                labeledField("姓名", driverName), labeledField("身份证号", driverIdCard),
                labeledField("从业资格证编码", driverLicenseNo), labeledField("发证机构", driverInstitution),
                labeledField("有效期（YYMM）", driverValidPeriod));
        Button insert = new Button("插卡上班");
        Button remove = new Button("拔卡下班");
        Button failure = new Button("模拟读卡失败");
        insert.setId("driver-insert");
        remove.setId("driver-remove");
        failure.setId("driver-failure");
        insert.setOnAction(event -> sendDriver(SignalClient.DriverAction.INSERT_CARD));
        remove.setOnAction(event -> sendDriver(SignalClient.DriverAction.REMOVE_CARD));
        failure.setOnAction(event -> sendDriver(SignalClient.DriverAction.READ_FAILURE));
        panel.getChildren().add(new HBox(8, insert, remove, failure));
        return panel;
    }

    private void sendDriver(SignalClient.DriverAction action) {
        DriverConfig driver = new DriverConfig(driverName.getText(), driverIdCard.getText(),
                driverLicenseNo.getText(), driverInstitution.getText(), driverValidPeriod.getText());
        operations.sendDriverCard(driver, action).whenComplete((ignored, failure) -> runOnFx(() -> {
            if (failure != null) {
                activity.setText("0702 发送失败: " + safeMessage(failure));
            } else {
                activity.setText(switch (action) {
                    case INSERT_CARD -> "0702 插卡上班已发送";
                    case REMOVE_CARD -> "0702 拔卡下班已发送";
                    case READ_FAILURE -> "0702 读卡失败已发送";
                });
            }
        }));
    }

    private static Label panelTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("panel-title");
        return label;
    }

    private static Node labeledField(String text, TextField field) {
        Label label = new Label(text);
        label.setMinWidth(130);
        HBox row = new HBox(8, label, field);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private static Tab tab(String title, Node content) {
        return new Tab(title, content);
    }

    private Node createMetrics() {
        GridPane metrics = new GridPane();
        metrics.getStyleClass().add("metrics-grid");
        metrics.setHgap(1);
        metrics.setVgap(1);
        for (int index = 0; index < 4; index++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(25);
            column.setHgrow(Priority.ALWAYS);
            metrics.getColumnConstraints().add(column);
        }
        metrics.add(metric("媒体状态", mediaStateValue), 0, 0);
        metrics.add(metric("目标节点", targetValue), 1, 0);
        metrics.add(metric("活动码流", streamValue), 2, 0);
        metrics.add(metric("音视频", tracksValue), 3, 0);
        metrics.add(metric("视频帧率", fpsValue), 0, 1);
        metrics.add(metric("发送码率", bitrateValue), 1, 1);
        metrics.add(metric("队列深度", queueValue), 2, 1);
        metrics.add(metric("丢帧", droppedValue), 3, 1);
        return metrics;
    }

    private Node createStatusBar() {
        // 行程指示放状态栏而不是页眉：960px 下页眉已经排满，再塞就会把「连接」按钮挤出可视区。
        HBox status = new HBox(12, ffmpegState, new Separator(Orientation.VERTICAL), deviceState,
                new Separator(Orientation.VERTICAL), tripState, new Separator(Orientation.VERTICAL),
                streamState, new Separator(Orientation.VERTICAL), lastReportState,
                new Separator(Orientation.VERTICAL), activity);
        HBox.setHgrow(activity, Priority.ALWAYS);
        status.getStyleClass().add("status-bar");
        status.setAlignment(Pos.CENTER_LEFT);
        return status;
    }

    private void toggleConnection() {
        if (connectionActive(currentSignalState)) {
            operations.disconnect();
            activity.setText("正在断开信令连接...");
            return;
        }
        SimulatorConfig config = validConfig();
        if (config == null || !save(config, false)) {
            return;
        }
        try {
            operations.connect(config);
            updateSignalState(SignalState.CONNECTING, "正在建立 TCP 连接");
        } catch (RuntimeException failure) {
            showActionError("连接失败", failure);
        }
    }

    private void saveConfiguration(boolean announce) {
        SimulatorConfig config = validConfig();
        if (config != null) {
            save(config, announce);
        }
    }

    private boolean save(SimulatorConfig config, boolean announce) {
        try {
            operations.saveConfig(config);
            configuration.clearErrors();
            if (announce) {
                activity.setText("配置已保存");
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            showActionError("保存配置失败", failure);
            return false;
        }
    }

    private SimulatorConfig validConfig() {
        ConfigValidation validation = configuration.data().validate();
        if (!validation.valid()) {
            configuration.showErrors(validation.errors());
            activity.setText("配置校验未通过");
            return null;
        }
        configuration.clearErrors();
        SimulatorConfig config = validation.config().orElseThrow();
        SimulatorConfig previous = operations.currentConfig();
        DriverConfig driver = new DriverConfig(driverName.getText(), driverIdCard.getText(),
                driverLicenseNo.getText(), driverInstitution.getText(), driverValidPeriod.getText());
        return new SimulatorConfig(config.signalHost(), config.signalPort(), config.version(),
                config.mobileNo(), config.deviceId(), config.channel(), config.registration(),
                config.ffmpegPath(), config.cameraName(), config.microphoneName(), config.mainProfile(),
                config.subProfile(), config.previewWidth(), config.previewHeight(), config.previewFps(),
                config.maxPayloadBytes(), config.trip(), driver, previous.alarm(), config.simFormat());
    }

    private void browseFfmpeg() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 FFmpeg 或 FFprobe 可执行文件");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        "FFmpeg 工具", "ffmpeg.exe", "ffmpeg", "ffprobe.exe", "ffprobe"),
                new FileChooser.ExtensionFilter("可执行文件", "*.exe"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        String configured = configuration.ffmpegPath();
        if (!configured.isBlank()) {
            try {
                Path path = Path.of(configured).toAbsolutePath();
                Path directory = Files.isDirectory(path) ? path : path.getParent();
                if (directory != null && Files.isDirectory(directory)) {
                    chooser.setInitialDirectory(directory.toFile());
                }
            } catch (RuntimeException ignored) {
                // A malformed typed path does not prevent choosing another file.
            }
        }
        File selected = chooser.showOpenDialog(owner());
        if (selected != null) {
            configuration.setFfmpegPath(
                    selected.toPath().toAbsolutePath().normalize().toString());
            detectFfmpeg();
        }
    }

    private void detectFfmpeg() {
        if (closed.get()) {
            return;
        }
        configuration.setFfmpegBusy(true);
        ffmpegSupported = false;
        ffmpegState.setText("FFmpeg 检测中...");
        replaceStateStyle(ffmpegState, "ffmpeg-pending");
        updatePreviewAction();
        operations.detectFfmpeg(configuration.ffmpegPath())
                .whenComplete((result, failure) -> runOnFx(() -> {
                    configuration.setFfmpegBusy(false);
                    if (failure != null) {
                        ffmpegSupported = false;
                        ffmpegState.setText("FFmpeg 检测失败");
                        replaceStateStyle(ffmpegState, "ffmpeg-error");
                        activity.setText(safeMessage(failure));
                    } else {
                        applyProbe(result);
                    }
                    updatePreviewAction();
                }));
    }

    private void applyProbe(FfmpegProbeResult result) {
        ffmpegSupported = result.supported();
        result.executable().ifPresent(path -> configuration.setFfmpegPath(path.toString()));
        configuration.setDevices(result.devices());
        ffmpegState.setText(result.summary());
        ffmpegState.setTooltip(result.diagnostics().isBlank()
                ? null : new Tooltip(result.diagnostics()));
        replaceStateStyle(ffmpegState,
                result.supported() ? "ffmpeg-ready" : "ffmpeg-error");
        activity.setText(probeActionMessage(result));
    }

    private void togglePreview() {
        if (previewActionRunning) {
            return;
        }
        if ("PREVIEW".equals(currentMediaState.state())) {
            previewActionRunning = true;
            updatePreviewAction();
            operations.stopPreview().whenComplete((ignored, failure) -> runOnFx(() -> {
                previewActionRunning = false;
                if (failure != null) {
                    showActionError("停止预览失败", failure);
                }
                updatePreviewAction();
            }));
            return;
        }
        if (!ffmpegSupported) {
            showActionError("FFmpeg 不可用", new IllegalStateException(
                    "请先选择并检测具备 DirectShow、libx264 和 pcm_alaw 的 FFmpeg"));
            return;
        }
        SimulatorConfig config = validConfig();
        if (config == null) {
            return;
        }
        if (config.cameraName().isBlank()) {
            configuration.showErrors(
                    Map.of(ConfigField.CAMERA, "请选择或输入摄像头名称"));
            activity.setText("未选择摄像头");
            return;
        }
        previewActionRunning = true;
        updatePreviewAction();
        operations.startPreview(config).whenComplete((ignored, failure) -> runOnFx(() -> {
            previewActionRunning = false;
            if (failure != null) {
                showActionError("启动预览失败", failure);
            }
            updatePreviewAction();
        }));
    }

    @Override
    public void onSignalState(SignalState state, String detail) {
        runOnFx(() -> updateSignalState(state, detail));
    }

    @Override
    public void onMediaState(MediaViewState state) {
        runOnFx(() -> updateMediaState(state));
    }

    @Override
    public void onTripState(TripViewState state) {
        runOnFx(() -> updateTripState(state));
    }

    @Override
    public void onPreviewFrame(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return;
        }
        runOnFx(() -> {
            try {
                Image decoded = new Image(new ByteArrayInputStream(jpeg));
                if (!decoded.isError()) {
                    previewImage.setImage(decoded);
                }
            } catch (RuntimeException invalidImage) {
                activity.setText("预览帧解码失败: " + safeMessage(invalidImage));
            }
        });
    }

    @Override
    public void onLog(LogEntry entry) {
        runOnFx(() -> {
            logs.add(entry);
            trimLogs();
            logView.scrollTo(logs.size() - 1);
        });
    }

    @Override
    public void onLocationReported(java.time.Instant timestamp) {
        runOnFx(() -> lastReportState.setText("最近上报 " + LOG_TIME.format(timestamp)));
    }

    @Override
    public void onDriverAction(String detail) {
        runOnFx(() -> activity.setText(detail));
    }

    @Override
    public void onError(String context, Throwable error) {
        runOnFx(() -> {
            if ("media-start".equals(context)) {
                showActionError("T9101 推流启动失败", error);
            } else {
                activity.setText(context + ": " + safeMessage(error));
            }
        });
    }

    public void reportStartupError(Throwable error) {
        runOnFx(() -> showActionError("加载本地配置失败", error));
    }

    private void updateSignalState(SignalState state, String detail) {
        currentSignalState = state;
        signalState.setText(signalText(state));
        signalState.setTooltip(detail == null || detail.isBlank() ? null : new Tooltip(detail));
        replaceStateStyle(signalState, signalStyle(state));
        boolean active = connectionActive(state);
        configuration.setLocked(active);
        saveButton.setDisable(active);
        connectButton.setText(active ? "断开" : "连接");
        connectButton.setDisable(state == SignalState.STOPPING);
        deviceState.setText("设备 " + configuration.data().deviceId());
        if (detail != null && !detail.isBlank()) {
            activity.setText(detail);
        }
    }

    /**
     * 行程按钮只有一个：未运行时开始，运行中停止。用当前状态而不是自己记一个布尔量决定动作——
     * 状态来自运行时，界面记的副本迟早会和它对不上。
     */
    private void toggleTrip() {
        if (operations.tripState().running()) {
            operations.stopTrip();
        } else {
            operations.startTrip();
        }
    }

    private void updateTripState(TripViewState state) {
        tripState.setText(state.summary());
        replaceStateStyle(tripState, state.running() ? "trip-running" : "trip-idle");
        configuration.setTripState(
                state.connected(), state.running(), state.planning(), state.explanation());
        // 降级说明是用户唯一能看到「为什么车不贴路」的地方，值得占用一次活动提示。
        if (!state.explanation().isBlank()) {
            activity.setText(state.explanation());
        }
    }

    private void updateMediaState(MediaViewState state) {
        currentMediaState = state;
        mediaStateValue.setText(mediaText(state.state()));
        streamState.setText("推流 " + mediaText(state.state()));        targetValue.setText(state.target());
        targetValue.setTooltip("-".equals(state.target()) ? null : new Tooltip(state.target()));
        streamValue.setText(state.stream());
        tracksValue.setText(trackText(state.audioEnabled(), state.videoEnabled()));
        fpsValue.setText("%.1f fps".formatted(state.framesPerSecond()));
        bitrateValue.setText(formatBitrate(state.bitsPerSecond()));
        queueValue.setText(Integer.toString(state.queueDepth()));
        droppedValue.setText(Long.toString(state.droppedFrames()));
        if (!state.detail().isBlank()) {
            activity.setText(state.detail());
        }
        if ("IDLE".equals(state.state()) || "FAILED".equals(state.state())) {
            previewImage.setImage(null);
        }
        updatePreviewAction();
    }

    private void updatePreviewAction() {
        boolean preview = "PREVIEW".equals(currentMediaState.state());
        boolean streaming = "STREAMING".equals(currentMediaState.state())
                || "PAUSED".equals(currentMediaState.state())
                || (!"-".equals(currentMediaState.target())
                        && "STARTING".equals(currentMediaState.state()));
        previewButton.setText(preview ? "停止预览"
                : streaming ? "推流预览" : "启动预览");
        previewButton.setDisable(
                previewActionRunning || streaming || (!preview && !ffmpegSupported));
    }

    private void trimLogs() {
        if (logs.size() > MAX_VISIBLE_LOGS) {
            logs.remove(0, logs.size() - MAX_VISIBLE_LOGS);
        }
    }

    private void showActionError(String title, Throwable failure) {
        Throwable cause = unwrap(failure);
        activity.setText(title + ": " + safeMessage(cause));
        if (getScene() == null || closed.get()) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("终端模拟器");
        alert.setHeaderText(title);
        alert.setContentText(safeMessage(cause));
        Window owner = owner();
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.show();
    }

    private Window owner() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable failure) {
        Throwable cause = unwrap(failure);
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    private static String probeActionMessage(FfmpegProbeResult result) {
        if (result.supported() && result.devices().videoDevices().isEmpty()) {
            return "未发现摄像头：请关闭占用程序、检查 Windows 相机隐私权限，然后刷新设备";
        }
        if (result.supported() && result.devices().audioDevices().isEmpty()) {
            return "未发现麦克风：请检查 Windows 麦克风隐私权限，然后刷新设备";
        }
        if (result.supported()) {
            return "采集设备已刷新，FFmpeg 路径已自动保存";
        }
        if (result.executable().isEmpty()) {
            return "未找到 FFmpeg：点击“浏览...”选择 ffmpeg.exe，或配置 PATH 后重启模拟器";
        }
        return result.summary() + "；请更换完整的 Windows FFmpeg 构建";
    }

    private static boolean connectionActive(SignalState state) {
        return state != SignalState.DISCONNECTED && state != SignalState.FAILED;
    }

    private static String signalText(SignalState state) {
        return switch (state) {
            case DISCONNECTED -> "808 未连接";
            case CONNECTING -> "808 连接中";
            case REGISTERING -> "808 注册中";
            case AUTHENTICATING -> "808 鉴权中";
            case ONLINE -> "808 已在线";
            case RECONNECT_WAIT -> "808 等待重连";
            case STOPPING -> "808 断开中";
            case FAILED -> "808 连接失败";
        };
    }

    private static String signalStyle(SignalState state) {
        return switch (state) {
            case ONLINE -> "state-online";
            case FAILED -> "state-error";
            case DISCONNECTED -> "state-offline";
            default -> "state-pending";
        };
    }

    private static String mediaText(String state) {
        return switch (state) {
            case "PREVIEW" -> "本地预览";
            case "STARTING" -> "启动中";
            case "STREAMING" -> "正在推流";
            case "PAUSED" -> "已暂停";
            case "FAILED" -> "媒体失败";
            case "STOPPING" -> "停止中";
            default -> "空闲";
        };
    }

    private static String trackText(boolean audio, boolean video) {
        if (audio && video) {
            return "音视频";
        }
        if (video) {
            return "视频";
        }
        if (audio) {
            return "音频";
        }
        return "-";
    }

    private static String formatBitrate(double bitsPerSecond) {
        if (bitsPerSecond >= 1_000_000) {
            return "%.2f Mbps".formatted(bitsPerSecond / 1_000_000);
        }
        return "%.0f Kbps".formatted(bitsPerSecond / 1_000);
    }

    private static VBox metric(String labelText, Label value) {
        Label label = new Label(labelText);
        label.getStyleClass().add("metric-label");
        VBox metric = new VBox(3, label, value);
        metric.getStyleClass().add("metric-cell");
        metric.setMinWidth(0);
        GridPane.setHgrow(metric, Priority.ALWAYS);
        return metric;
    }

    private static Label metricValue() {
        Label value = new Label("-");
        value.getStyleClass().add("metric-value");
        value.setMaxWidth(Double.MAX_VALUE);
        value.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        return value;
    }

    private static void replaceStateStyle(Label label, String style) {
        label.getStyleClass().removeAll(
                "state-online", "state-offline", "state-pending", "state-error",
                "ffmpeg-ready", "ffmpeg-pending", "ffmpeg-error",
                "trip-running", "trip-idle");
        label.getStyleClass().add(style);
    }

    private void runOnFx(Runnable action) {
        if (closed.get()) {
            return;
        }
        Runnable guardedAction = () -> {
            if (!closed.get()) {
                action.run();
            }
        };
        if (Platform.isFxApplicationThread()) {
            guardedAction.run();
        } else {
            Platform.runLater(guardedAction);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        statisticsTimer.stop();
        operations.setListener(RuntimeListener.NOOP);
        previewImage.setImage(null);
        operations.close();
    }

    private static final class LogCell extends ListCell<LogEntry> {
        @Override
        protected void updateItem(LogEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            getStyleClass().removeAll("log-info", "log-warn", "log-error");
            if (empty || entry == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            String formatted = "%s  %-5s  %-9s  %s".formatted(
                    LOG_TIME.format(entry.timestamp()),
                    entry.level(),
                    '[' + entry.component() + ']',
                    entry.message());
            setText(formatted);
            setWrapText(true);
            setTooltip(new Tooltip(formatted));
            getStyleClass().add("log-" + entry.level().name().toLowerCase(Locale.ROOT));
        }
    }
}
