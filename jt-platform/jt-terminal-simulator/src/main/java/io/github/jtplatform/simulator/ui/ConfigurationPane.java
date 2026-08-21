package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.Jt808Version;
import org.yzh.protocol.t1078.codec.Jt1078SimFormat;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.media.DirectShowDevice;
import io.github.jtplatform.simulator.media.DirectShowDevices;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

final class ConfigurationPane extends VBox {
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");

    private final EnumMap<ConfigField, Control> controls = new EnumMap<>(ConfigField.class);
    private final EnumMap<ConfigField, Label> errorLabels = new EnumMap<>(ConfigField.class);
    private final EnumMap<ConfigField, Tab> fieldTabs = new EnumMap<>(ConfigField.class);

    private final TextField signalHost = text(ConfigField.SIGNAL_HOST);
    private final TextField signalPort = text(ConfigField.SIGNAL_PORT);
    private final ComboBox<Jt808Version> version = combo(ConfigField.VERSION);
    private final TextField mobileNo = text(ConfigField.MOBILE_NO);
    private final TextField deviceId = text(ConfigField.DEVICE_ID);
    private final TextField channel = text(ConfigField.CHANNEL);
    private final TextField provinceId = text(ConfigField.PROVINCE_ID);
    private final TextField cityId = text(ConfigField.CITY_ID);
    private final TextField makerId = text(ConfigField.MAKER_ID);
    private final TextField deviceModel = text(ConfigField.DEVICE_MODEL);
    private final TextField plateColor = text(ConfigField.PLATE_COLOR);
    private final TextField plateNo = text(ConfigField.PLATE_NO);
    private final TextField imei = text(ConfigField.IMEI);
    private final TextField softwareVersion = text(ConfigField.SOFTWARE_VERSION);

    private final TextField ffmpegPath = text(ConfigField.FFMPEG_PATH);
    private final ComboBox<String> camera = combo(ConfigField.CAMERA);
    private final ComboBox<String> microphone = combo(ConfigField.MICROPHONE);
    private final TextField previewWidth = text(ConfigField.PREVIEW_WIDTH);
    private final TextField previewHeight = text(ConfigField.PREVIEW_HEIGHT);
    private final TextField previewFrameRate = text(ConfigField.PREVIEW_FRAME_RATE);

    private final TextField mainWidth = text(ConfigField.MAIN_WIDTH);
    private final TextField mainHeight = text(ConfigField.MAIN_HEIGHT);
    private final TextField mainFrameRate = text(ConfigField.MAIN_FRAME_RATE);
    private final TextField mainBitrate = text(ConfigField.MAIN_BITRATE);
    private final TextField mainGop = text(ConfigField.MAIN_GOP);
    private final TextField subWidth = text(ConfigField.SUB_WIDTH);
    private final TextField subHeight = text(ConfigField.SUB_HEIGHT);
    private final TextField subFrameRate = text(ConfigField.SUB_FRAME_RATE);
    private final TextField subBitrate = text(ConfigField.SUB_BITRATE);
    private final TextField subGop = text(ConfigField.SUB_GOP);
    private final TextField maxPayloadBytes = text(ConfigField.MAX_PAYLOAD_BYTES);
    private final ComboBox<Jt1078SimFormat> simFormat = combo(ConfigField.SIM_FORMAT);

    private final CheckBox tripAutoStart = check(ConfigField.TRIP_AUTO_START, "连接成功后自动开始");
    private final TextField tripAmapKey = text(ConfigField.TRIP_AMAP_KEY);
    private final TextField tripOriginLat = text(ConfigField.TRIP_ORIGIN_LAT);
    private final TextField tripOriginLng = text(ConfigField.TRIP_ORIGIN_LNG);
    private final TextField tripDestinationLat = text(ConfigField.TRIP_DESTINATION_LAT);
    private final TextField tripDestinationLng = text(ConfigField.TRIP_DESTINATION_LNG);
    private final TextField tripSpeed = text(ConfigField.TRIP_SPEED);
    private final TextField tripReportInterval = text(ConfigField.TRIP_REPORT_INTERVAL);
    private final CheckBox tripRoundTrip = check(ConfigField.TRIP_ROUND_TRIP, "到终点后原路返回并循环");

    private final Button browseFfmpeg = new Button("浏览...");
    private final Button detectFfmpeg = new Button("检测 FFmpeg");
    private final Button refreshDevices = new Button("刷新设备");
    private final Button tripToggle = new Button("开始行程");
    private final ProgressIndicator tripProgress = new ProgressIndicator();
    private final Label tripHint = new Label();
    private final Label validationSummary = new Label();
    private final TabPane tabs = new TabPane();

    private boolean locked;
    private boolean ffmpegBusy;
    private boolean tripConnected;
    private boolean tripRunning;
    private boolean tripPlanning;

    /**
     * 手机号不足所选版本要求的位数时，左侧补零并刷新提示文案。
     * 前导零是 BCD 定宽字段的合法填充位，无需用户手动补全。
     */
    private void padMobileNoToVersion(Jt808Version current) {
        if (current == null) {
            return;
        }
        int required = current.mobileNumberLength();
        mobileNo.setPromptText(required + " 位数字，不足时自动前补 0");
        String value = mobileNo.getText() == null ? "" : mobileNo.getText().trim();
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            return;
        }
        if (value.length() < required) {
            mobileNo.setText("0".repeat(required - value.length()) + value);
        }
    }

    ConfigurationPane(SimulatorConfig initialConfig) {
        getStyleClass().add("configuration-pane");
        setMinWidth(410);
        setPrefWidth(455);
        setMaxWidth(540);

        version.getItems().setAll(Jt808Version.values());
        simFormat.getItems().setAll(Jt1078SimFormat.values());
        simFormat.setConverter(new StringConverter<>() {
            @Override public String toString(Jt1078SimFormat value) {
                return value == null ? "" : switch (value) {
                    case STANDARD -> "标准 12 位";
                    case EXTENDED -> "扩展 20 位 · 置 X 位";
                    case NON_STANDARD_20 -> "野生 20 位 · 不置 X 位";
                };
            }
            @Override public Jt1078SimFormat fromString(String value) { return null; }
        });
        simFormat.setTooltip(new Tooltip("野生 20 位用于复现不守标准的设备，验证平台自动识别"));
        version.setConverter(new StringConverter<>() {
            @Override
            public String toString(Jt808Version value) {
                return value == null ? "" : switch (value) {
                    case V2013 -> "JT/T 808-2013";
                    case V2019 -> "JT/T 808-2019";
                };
            }

            @Override
            public Jt808Version fromString(String value) {
                return null;
            }
        });
        // 手机号是定宽 BCD 字段，切换版本时位数要求从 12 位变 20 位（或反向）；
        // 输入框内的纯数字不足位数时自动前补零，并同步更新提示文案。
        version.valueProperty().addListener((observable, previous, current) ->
                padMobileNoToVersion(current));
        camera.setEditable(true);
        microphone.setEditable(true);
        camera.setPromptText("选择或输入摄像头名称");
        microphone.setPromptText("选择或输入麦克风名称");

        tripToggle.setId("trip-toggle");
        tripAmapKey.setPromptText("留空则使用内置路线");
        tripOriginLat.setPromptText("留空 = 用内置起点");
        tripOriginLng.setPromptText("留空 = 用内置起点");
        tripDestinationLat.setPromptText("留空 = 用内置终点");
        tripDestinationLng.setPromptText("留空 = 用内置终点");
        tripProgress.setMaxSize(16, 16);
        tripProgress.setVisible(false);
        tripProgress.setManaged(false);
        tripHint.getStyleClass().add("field-hint");
        tripHint.setWrapText(true);

        // 「连接」页之后紧接「行程」：行程与连接的关联远比与采集、编码的关联紧密。
        Tab connectionTab = tab("连接", connectionContent());
        Tab tripTab = tab("行程", tripContent());
        Tab captureTab = tab("采集", captureContent());
        Tab encodingTab = tab("编码", encodingContent());
        tabs.getTabs().setAll(connectionTab, tripTab, captureTab, encodingTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        mapTab(connectionTab,
                ConfigField.SIGNAL_HOST, ConfigField.SIGNAL_PORT, ConfigField.VERSION,
                ConfigField.MOBILE_NO, ConfigField.DEVICE_ID, ConfigField.CHANNEL,
                ConfigField.PROVINCE_ID, ConfigField.CITY_ID, ConfigField.MAKER_ID,
                ConfigField.DEVICE_MODEL, ConfigField.PLATE_COLOR, ConfigField.PLATE_NO,
                ConfigField.IMEI, ConfigField.SOFTWARE_VERSION);
        mapTab(tripTab,
                ConfigField.TRIP_AUTO_START, ConfigField.TRIP_AMAP_KEY,
                ConfigField.TRIP_ORIGIN_LAT, ConfigField.TRIP_ORIGIN_LNG,
                ConfigField.TRIP_DESTINATION_LAT, ConfigField.TRIP_DESTINATION_LNG,
                ConfigField.TRIP_SPEED, ConfigField.TRIP_REPORT_INTERVAL,
                ConfigField.TRIP_ROUND_TRIP);
        mapTab(captureTab,
                ConfigField.FFMPEG_PATH, ConfigField.CAMERA, ConfigField.MICROPHONE,
                ConfigField.PREVIEW_WIDTH, ConfigField.PREVIEW_HEIGHT,
                ConfigField.PREVIEW_FRAME_RATE);
        mapTab(encodingTab,
                ConfigField.MAIN_WIDTH, ConfigField.MAIN_HEIGHT,
                ConfigField.MAIN_FRAME_RATE, ConfigField.MAIN_BITRATE, ConfigField.MAIN_GOP,
                ConfigField.SUB_WIDTH, ConfigField.SUB_HEIGHT,
                ConfigField.SUB_FRAME_RATE, ConfigField.SUB_BITRATE, ConfigField.SUB_GOP,
                ConfigField.MAX_PAYLOAD_BYTES);

        validationSummary.getStyleClass().add("validation-summary");
        validationSummary.setManaged(false);
        validationSummary.setVisible(false);
        validationSummary.setWrapText(true);

        getChildren().setAll(validationSummary, tabs);
        apply(SimulatorFormData.from(initialConfig));
        setTripState(false, false, false, "");
    }

    private Node connectionContent() {
        VBox content = tabContent();
        GridPane endpoint = grid();
        row(endpoint, 0, "信令地址", ConfigField.SIGNAL_HOST, signalHost);
        row(endpoint, 1, "信令端口", ConfigField.SIGNAL_PORT, signalPort);
        row(endpoint, 2, "808 版本", ConfigField.VERSION, version);
        row(endpoint, 3, "终端手机号", ConfigField.MOBILE_NO, mobileNo);
        row(endpoint, 4, "设备 ID", ConfigField.DEVICE_ID, deviceId);
        row(endpoint, 5, "逻辑通道", ConfigField.CHANNEL, channel);
        content.getChildren().addAll(sectionTitle("信令与终端"), endpoint);

        GridPane registration = grid();
        row(registration, 0, "省域 ID", ConfigField.PROVINCE_ID, provinceId);
        row(registration, 1, "市县域 ID", ConfigField.CITY_ID, cityId);
        row(registration, 2, "制造商 ID", ConfigField.MAKER_ID, makerId);
        row(registration, 3, "终端型号", ConfigField.DEVICE_MODEL, deviceModel);
        row(registration, 4, "车牌颜色", ConfigField.PLATE_COLOR, plateColor);
        row(registration, 5, "车牌号", ConfigField.PLATE_NO, plateNo);
        row(registration, 6, "IMEI", ConfigField.IMEI, imei);
        row(registration, 7, "软件版本", ConfigField.SOFTWARE_VERSION, softwareVersion);
        content.getChildren().addAll(sectionTitle("注册信息"), registration);
        return scroll(content);
    }

    /**
     * 「行程」页。控件按阅读顺序添加——密钥、起点、终点、参数——以保证 Tab 键顺序与视觉顺序一致。
     */
    private Node tripContent() {
        VBox content = tabContent();

        GridPane source = grid();
        row(source, 0, "地图密钥", ConfigField.TRIP_AMAP_KEY, tripAmapKey);
        Label sourceHint = new Label(
                "填入高德「Web 服务」类型的 Key 后，行程会沿真实道路行驶；留空则使用内置路线。");
        sourceHint.getStyleClass().add("field-hint");
        sourceHint.setWrapText(true);
        content.getChildren().addAll(sectionTitle("路线来源"), source, sourceHint);

        GridPane endpoints = grid();
        row(endpoints, 0, "起点纬度", ConfigField.TRIP_ORIGIN_LAT, tripOriginLat);
        row(endpoints, 1, "起点经度", ConfigField.TRIP_ORIGIN_LNG, tripOriginLng);
        row(endpoints, 2, "终点纬度", ConfigField.TRIP_DESTINATION_LAT, tripDestinationLat);
        row(endpoints, 3, "终点经度", ConfigField.TRIP_DESTINATION_LNG, tripDestinationLng);
        Label endpointHint = new Label(
                "坐标为高德坐标系（GCJ-02），可直接从高德地图拾取；四项全部留空即使用内置路线。");
        endpointHint.getStyleClass().add("field-hint");
        endpointHint.setWrapText(true);
        content.getChildren().addAll(sectionTitle("起点与终点"), endpoints, endpointHint);

        GridPane parameters = grid();
        row(parameters, 0, "速度 (km/h)", ConfigField.TRIP_SPEED, tripSpeed);
        row(parameters, 1, "上报间隔 (秒)", ConfigField.TRIP_REPORT_INTERVAL, tripReportInterval);
        row(parameters, 2, "往返", ConfigField.TRIP_ROUND_TRIP, tripRoundTrip);
        row(parameters, 3, "自动开始", ConfigField.TRIP_AUTO_START, tripAutoStart);
        content.getChildren().addAll(sectionTitle("行驶参数"), parameters);

        HBox actions = new HBox(8, tripProgress, tripToggle);
        actions.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().addAll(actions, tripHint);
        return scroll(content);
    }

    private Node captureContent() {
        VBox content = tabContent();
        GridPane ffmpeg = grid();
        HBox pathRow = new HBox(6, ffmpegPath, browseFfmpeg);
        HBox.setHgrow(ffmpegPath, Priority.ALWAYS);
        compositeRow(ffmpeg, 0, "FFmpeg", ConfigField.FFMPEG_PATH, ffmpegPath, pathRow);
        HBox actions = new HBox(8, detectFfmpeg, refreshDevices);
        actions.setAlignment(Pos.CENTER_RIGHT);
        ffmpeg.add(actions, 1, 1);
        content.getChildren().addAll(sectionTitle("FFmpeg"), ffmpeg);

        GridPane devices = grid();
        row(devices, 0, "摄像头", ConfigField.CAMERA, camera);
        row(devices, 1, "麦克风", ConfigField.MICROPHONE, microphone);
        content.getChildren().addAll(sectionTitle("采集设备"), devices);

        GridPane preview = grid();
        row(preview, 0, "预览宽度", ConfigField.PREVIEW_WIDTH, previewWidth);
        row(preview, 1, "预览高度", ConfigField.PREVIEW_HEIGHT, previewHeight);
        row(preview, 2, "预览帧率", ConfigField.PREVIEW_FRAME_RATE, previewFrameRate);
        content.getChildren().addAll(sectionTitle("实时预览"), preview);
        return scroll(content);
    }

    private Node encodingContent() {
        VBox content = tabContent();
        GridPane main = profileGrid(
                ConfigField.MAIN_WIDTH, mainWidth,
                ConfigField.MAIN_HEIGHT, mainHeight,
                ConfigField.MAIN_FRAME_RATE, mainFrameRate,
                ConfigField.MAIN_BITRATE, mainBitrate,
                ConfigField.MAIN_GOP, mainGop);
        content.getChildren().addAll(sectionTitle("主码流"), main);

        GridPane sub = profileGrid(
                ConfigField.SUB_WIDTH, subWidth,
                ConfigField.SUB_HEIGHT, subHeight,
                ConfigField.SUB_FRAME_RATE, subFrameRate,
                ConfigField.SUB_BITRATE, subBitrate,
                ConfigField.SUB_GOP, subGop);
        content.getChildren().addAll(sectionTitle("子码流"), sub);

        GridPane packet = grid();
        row(packet, 0, "1078 单片载荷", ConfigField.MAX_PAYLOAD_BYTES, maxPayloadBytes);
        row(packet, 1, "SIM 形态", ConfigField.SIM_FORMAT, simFormat);
        Label simHint = new Label("野生 20 位用于复现不守标准的设备，验证平台自动识别");
        simHint.getStyleClass().add("field-hint");
        simHint.setWrapText(true);
        content.getChildren().addAll(sectionTitle("报文"), packet, simHint);
        return scroll(content);
    }

    private GridPane profileGrid(
            ConfigField widthField,
            TextField width,
            ConfigField heightField,
            TextField height,
            ConfigField frameRateField,
            TextField frameRate,
            ConfigField bitrateField,
            TextField bitrate,
            ConfigField gopField,
            TextField gop) {
        GridPane grid = grid();
        row(grid, 0, "宽度", widthField, width);
        row(grid, 1, "高度", heightField, height);
        row(grid, 2, "帧率", frameRateField, frameRate);
        row(grid, 3, "码率 (Kbps)", bitrateField, bitrate);
        row(grid, 4, "GOP (秒)", gopField, gop);
        return grid;
    }

    SimulatorFormData data() {
        return new SimulatorFormData(
                signalHost.getText(),
                signalPort.getText(),
                version.getValue(),
                mobileNo.getText(),
                deviceId.getText(),
                channel.getText(),
                provinceId.getText(),
                cityId.getText(),
                makerId.getText(),
                deviceModel.getText(),
                plateColor.getText(),
                plateNo.getText(),
                imei.getText(),
                softwareVersion.getText(),
                ffmpegPath.getText(),
                editableValue(camera),
                editableValue(microphone),
                new ProfileFormData(
                        mainWidth.getText(), mainHeight.getText(), mainFrameRate.getText(),
                        mainBitrate.getText(), mainGop.getText()),
                new ProfileFormData(
                        subWidth.getText(), subHeight.getText(), subFrameRate.getText(),
                        subBitrate.getText(), subGop.getText()),
                previewWidth.getText(),
                previewHeight.getText(),
                previewFrameRate.getText(),
                maxPayloadBytes.getText(),
                new TripFormData(
                        tripAutoStart.isSelected(),
                        tripAmapKey.getText(),
                        tripOriginLat.getText(),
                        tripOriginLng.getText(),
                        tripDestinationLat.getText(),
                        tripDestinationLng.getText(),
                        tripSpeed.getText(),
                        tripReportInterval.getText(),
                        tripRoundTrip.isSelected()),
                simFormat.getValue(), "", "", "", "", "", "80");
    }

    void apply(SimulatorFormData data) {
        signalHost.setText(data.signalHost());
        signalPort.setText(data.signalPort());
        version.setValue(data.version());
        mobileNo.setText(data.mobileNo());
        padMobileNoToVersion(data.version());
        deviceId.setText(data.deviceId());
        channel.setText(data.channel());
        provinceId.setText(data.provinceId());
        cityId.setText(data.cityId());
        makerId.setText(data.makerId());
        deviceModel.setText(data.deviceModel());
        plateColor.setText(data.plateColor());
        plateNo.setText(data.plateNo());
        imei.setText(data.imei());
        softwareVersion.setText(data.softwareVersion());
        ffmpegPath.setText(data.ffmpegPath());
        setEditableValue(camera, data.cameraName());
        setEditableValue(microphone, data.microphoneName());
        applyProfile(data.mainProfile(),
                mainWidth, mainHeight, mainFrameRate, mainBitrate, mainGop);
        applyProfile(data.subProfile(),
                subWidth, subHeight, subFrameRate, subBitrate, subGop);
        previewWidth.setText(data.previewWidth());
        previewHeight.setText(data.previewHeight());
        previewFrameRate.setText(data.previewFrameRate());
        maxPayloadBytes.setText(data.maxPayloadBytes());
        simFormat.setValue(data.simFormat());
        TripFormData trip = data.trip();
        tripAutoStart.setSelected(trip.autoStart());
        tripAmapKey.setText(trip.amapKey());
        tripOriginLat.setText(trip.originLat());
        tripOriginLng.setText(trip.originLng());
        tripDestinationLat.setText(trip.destinationLat());
        tripDestinationLng.setText(trip.destinationLng());
        tripSpeed.setText(trip.speedKph());
        tripReportInterval.setText(trip.reportIntervalSeconds());
        tripRoundTrip.setSelected(trip.roundTrip());
        clearErrors();
    }

    Button tripToggleButton() {
        return tripToggle;
    }

    /**
     * 更新行程按钮与提示。
     *
     * <p>未连接时按钮置灰，并用 tooltip 与页内提示说明**为什么**不可点——只置灰不解释，用户只会
     * 反复点击然后以为程序坏了。行程唯一的可观测副作用是通过连接发位置汇报，没有连接就「开着但
     * 什么都没发生」，那比置灰更让人困惑。
     */
    void setTripState(boolean connected, boolean running, boolean planning, String hint) {
        this.tripConnected = connected;
        this.tripRunning = running;
        this.tripPlanning = planning;
        tripHint.setText(hint == null ? "" : hint);
        tripHint.setManaged(!tripHint.getText().isEmpty());
        tripHint.setVisible(!tripHint.getText().isEmpty());
        updateTripActionState();
    }

    private void updateTripActionState() {
        // 规划路线最长可能等待数秒，必须给出进度反馈，否则用户会以为点击没有生效。
        tripProgress.setVisible(tripPlanning);
        tripProgress.setManaged(tripPlanning);
        tripToggle.setText(tripPlanning ? "规划路线中…" : (tripRunning ? "停止行程" : "开始行程"));
        tripToggle.setDisable(!tripConnected || tripPlanning);
        if (!tripConnected) {
            tripToggle.setTooltip(new Tooltip("请先连接平台，行程需要通过已建立的会话上报位置"));
        } else if (tripPlanning) {
            tripToggle.setTooltip(new Tooltip("正在获取路线，请稍候"));
        } else {
            tripToggle.setTooltip(null);
        }
    }

    void setDevices(DirectShowDevices devices) {
        replaceItems(camera, devices.videoDevices().stream().map(DirectShowDevice::name).toList());
        replaceItems(microphone,
                devices.audioDevices().stream().map(DirectShowDevice::name).toList());
    }

    void setFfmpegPath(String path) {
        ffmpegPath.setText(path == null ? "" : path);
    }

    String ffmpegPath() {
        return ffmpegPath.getText().trim();
    }

    Button browseFfmpegButton() {
        return browseFfmpeg;
    }

    Button detectFfmpegButton() {
        return detectFfmpeg;
    }

    Button refreshDevicesButton() {
        return refreshDevices;
    }

    void setLocked(boolean locked) {
        this.locked = locked;
        controls.values().forEach(control -> control.setDisable(locked));
        updateActionState();
    }

    void setFfmpegBusy(boolean busy) {
        this.ffmpegBusy = busy;
        updateActionState();
    }

    void showErrors(Map<ConfigField, String> errors) {
        clearErrors();
        if (errors.isEmpty()) {
            return;
        }
        for (Map.Entry<ConfigField, String> error : errors.entrySet()) {
            Control control = controls.get(error.getKey());
            Label errorLabel = errorLabels.get(error.getKey());
            if (control != null) {
                control.pseudoClassStateChanged(INVALID, true);
                control.setTooltip(new Tooltip(error.getValue()));
            }
            if (errorLabel != null) {
                errorLabel.setText(error.getValue());
                errorLabel.setVisible(true);
            }
        }
        validationSummary.setText(errors.size() == 1
                ? errors.values().iterator().next()
                : "请修正 " + errors.size() + " 个配置字段");
        validationSummary.setManaged(true);
        validationSummary.setVisible(true);
        ConfigField first = errors.keySet().stream()
                .filter(controls::containsKey)
                .findFirst()
                .orElse(null);
        if (first != null) {
            Tab target = fieldTabs.get(first);
            if (target != null) {
                tabs.getSelectionModel().select(target);
            }
            controls.get(first).requestFocus();
        }
    }

    void clearErrors() {
        controls.values().forEach(control -> {
            control.pseudoClassStateChanged(INVALID, false);
            control.setTooltip(null);
        });
        errorLabels.values().forEach(label -> {
            label.setText("");
            label.setVisible(false);
        });
        validationSummary.setText("");
        validationSummary.setManaged(false);
        validationSummary.setVisible(false);
    }

    private void updateActionState() {
        browseFfmpeg.setDisable(locked || ffmpegBusy);
        detectFfmpeg.setDisable(locked || ffmpegBusy);
        refreshDevices.setDisable(locked || ffmpegBusy);
    }

    private static void applyProfile(
            ProfileFormData profile,
            TextField width,
            TextField height,
            TextField frameRate,
            TextField bitrate,
            TextField gop) {
        width.setText(profile.width());
        height.setText(profile.height());
        frameRate.setText(profile.frameRate());
        bitrate.setText(profile.bitrateKbps());
        gop.setText(profile.gopSeconds());
    }

    private static String editableValue(ComboBox<String> comboBox) {
        String editorText = comboBox.getEditor().getText();
        return editorText == null ? "" : editorText.trim();
    }

    private static void setEditableValue(ComboBox<String> comboBox, String value) {
        comboBox.setValue(value == null || value.isBlank() ? null : value);
        comboBox.getEditor().setText(value == null ? "" : value);
    }

    private static void replaceItems(ComboBox<String> comboBox, List<String> discovered) {
        String selected = editableValue(comboBox);
        Set<String> items = new LinkedHashSet<>(discovered);
        if (!selected.isBlank()) {
            items.add(selected);
        }
        comboBox.getItems().setAll(items);
        String next = selected.isBlank() && !discovered.isEmpty() ? discovered.getFirst() : selected;
        setEditableValue(comboBox, next);
    }

    private TextField text(ConfigField field) {
        TextField control = register(field, new TextField());
        control.setPrefColumnCount(16);
        return control;
    }

    /**
     * 复选框控件工厂。文字写在控件自身上，因此左侧标签只承担分组说明的作用。
     */
    private CheckBox check(ConfigField field, String text) {
        return register(field, new CheckBox(text));
    }

    private <T> ComboBox<T> combo(ConfigField field) {
        ComboBox<T> control = register(field, new ComboBox<>());
        control.setMaxWidth(Double.MAX_VALUE);
        return control;
    }

    private <T extends Control> T register(ConfigField field, T control) {
        control.setId(field.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        control.setMaxWidth(Double.MAX_VALUE);
        controls.put(field, control);
        return control;
    }

    private void row(
            GridPane grid,
            int row,
            String labelText,
            ConfigField field,
            Control control) {
        compositeRow(grid, row, labelText, field, control, control);
    }

    private void compositeRow(
            GridPane grid,
            int row,
            String labelText,
            ConfigField field,
            Control control,
            Node input) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setLabelFor(control);
        Label error = new Label();
        error.getStyleClass().add("field-error");
        error.setWrapText(true);
        error.setMinHeight(16);
        error.setVisible(false);
        errorLabels.put(field, error);
        VBox fieldBox = new VBox(3, input, error);
        GridPane.setHgrow(fieldBox, Priority.ALWAYS);
        grid.add(label, 0, row);
        grid.add(fieldBox, 1, row);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.setHgap(12);
        grid.setVgap(8);
        return grid;
    }

    private static VBox tabContent() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(14, 16, 18, 16));
        return content;
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("config-scroll");
        return scroll;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static Tab tab(String text, Node content) {
        return new Tab(text, content);
    }

    private void mapTab(Tab tab, ConfigField... fields) {
        for (ConfigField field : fields) {
            fieldTabs.put(field, tab);
        }
    }
}
