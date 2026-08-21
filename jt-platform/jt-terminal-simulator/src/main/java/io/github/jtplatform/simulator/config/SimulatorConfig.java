package io.github.jtplatform.simulator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.yzh.protocol.t1078.codec.Jt1078SimFormat;
import java.util.Objects;

public record SimulatorConfig(
        String signalHost,
        int signalPort,
        Jt808Version version,
        String mobileNo,
        String deviceId,
        int channel,
        RegistrationConfig registration,
        String ffmpegPath,
        String cameraName,
        String microphoneName,
        VideoProfile mainProfile,
        VideoProfile subProfile,
        int previewWidth,
        int previewHeight,
        int previewFps,
        int maxPayloadBytes,
        TripConfig trip,
        DriverConfig driver,
        AlarmConfig alarm,
        Jt1078SimFormat simFormat,
        RecordingConfig recording,
        FleetConfig fleet,
        TerminalManagementConfig terminalManagement,
        WaybillConfig waybill) {

    public static final int DEFAULT_SIGNAL_PORT = 7_100;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 1_400;

    /** 保持既有调用方源码兼容；新增字段按默认值接入。 */
    public SimulatorConfig(
            String signalHost, int signalPort, Jt808Version version, String mobileNo,
            String deviceId, int channel, RegistrationConfig registration, String ffmpegPath,
            String cameraName, String microphoneName, VideoProfile mainProfile,
            VideoProfile subProfile, int previewWidth, int previewHeight, int previewFps,
            int maxPayloadBytes, TripConfig trip) {
        this(signalHost, signalPort, version, mobileNo, deviceId, channel, registration,
                ffmpegPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip,
                DriverConfig.defaults(), AlarmConfig.defaults(), Jt1078SimFormat.STANDARD,
                RecordingConfig.defaults(), FleetConfig.defaults(), TerminalManagementConfig.defaults(),
                WaybillConfig.defaults());
    }

    /** 在已有功能字段之后加入录像配置，保持上一变更的源码调用兼容。 */
    public SimulatorConfig(
            String signalHost, int signalPort, Jt808Version version, String mobileNo,
            String deviceId, int channel, RegistrationConfig registration, String ffmpegPath,
            String cameraName, String microphoneName, VideoProfile mainProfile,
            VideoProfile subProfile, int previewWidth, int previewHeight, int previewFps,
            int maxPayloadBytes, TripConfig trip, DriverConfig driver, AlarmConfig alarm,
            Jt1078SimFormat simFormat) {
        this(signalHost, signalPort, version, mobileNo, deviceId, channel, registration,
                ffmpegPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip, driver, alarm,
                simFormat, RecordingConfig.defaults(), FleetConfig.defaults());
    }

    public SimulatorConfig(
            String signalHost, int signalPort, Jt808Version version, String mobileNo,
            String deviceId, int channel, RegistrationConfig registration, String ffmpegPath,
            String cameraName, String microphoneName, VideoProfile mainProfile,
            VideoProfile subProfile, int previewWidth, int previewHeight, int previewFps,
            int maxPayloadBytes, TripConfig trip, DriverConfig driver, AlarmConfig alarm,
            Jt1078SimFormat simFormat, RecordingConfig recording) {
        this(signalHost, signalPort, version, mobileNo, deviceId, channel, registration,
                ffmpegPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip, driver, alarm,
                simFormat, recording, FleetConfig.defaults(), TerminalManagementConfig.defaults(),
                WaybillConfig.defaults());
    }

    /** 在车队配置之后加入终端管理状态，保持既有源码调用兼容。 */
    public SimulatorConfig(
            String signalHost, int signalPort, Jt808Version version, String mobileNo,
            String deviceId, int channel, RegistrationConfig registration, String ffmpegPath,
            String cameraName, String microphoneName, VideoProfile mainProfile,
            VideoProfile subProfile, int previewWidth, int previewHeight, int previewFps,
            int maxPayloadBytes, TripConfig trip, DriverConfig driver, AlarmConfig alarm,
            Jt1078SimFormat simFormat, RecordingConfig recording, FleetConfig fleet) {
        this(signalHost, signalPort, version, mobileNo, deviceId, channel, registration,
                ffmpegPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip, driver, alarm,
                simFormat, recording, fleet, TerminalManagementConfig.defaults(), WaybillConfig.defaults());
    }

    /** 在终端管理状态之后加入运单配置，保持既有源码调用兼容。 */
    public SimulatorConfig(
            String signalHost, int signalPort, Jt808Version version, String mobileNo,
            String deviceId, int channel, RegistrationConfig registration, String ffmpegPath,
            String cameraName, String microphoneName, VideoProfile mainProfile,
            VideoProfile subProfile, int previewWidth, int previewHeight, int previewFps,
            int maxPayloadBytes, TripConfig trip, DriverConfig driver, AlarmConfig alarm,
            Jt1078SimFormat simFormat, RecordingConfig recording, FleetConfig fleet,
            TerminalManagementConfig terminalManagement) {
        this(signalHost, signalPort, version, mobileNo, deviceId, channel, registration,
                ffmpegPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip, driver, alarm,
                simFormat, recording, fleet, terminalManagement, WaybillConfig.defaults());
    }

    public SimulatorConfig {
        signalHost = requireText(signalHost, "signalHost");
        if (signalPort < 1 || signalPort > 65_535) {
            throw new IllegalArgumentException("signalPort must be in range 1..65535");
        }
        version = Objects.requireNonNull(version, "version");
        mobileNo = requireDigits(mobileNo, "mobileNo", version.mobileNumberLength(), version.mobileNumberLength());
        deviceId = requireDigits(deviceId, "deviceId", 1, 12);
        if (channel < 1 || channel > 255) {
            throw new IllegalArgumentException("channel must be in range 1..255");
        }
        registration = Objects.requireNonNull(registration, "registration");
        ffmpegPath = normalizeOptional(ffmpegPath, "ffmpegPath");
        cameraName = normalizeOptional(cameraName, "cameraName");
        microphoneName = normalizeOptional(microphoneName, "microphoneName");
        mainProfile = Objects.requireNonNull(mainProfile, "mainProfile");
        subProfile = Objects.requireNonNull(subProfile, "subProfile");
        if (previewWidth < 160 || previewWidth > 3_840 || (previewWidth & 1) != 0) {
            throw new IllegalArgumentException("previewWidth must be an even value in range 160..3840");
        }
        if (previewHeight < 120 || previewHeight > 2_160 || (previewHeight & 1) != 0) {
            throw new IllegalArgumentException("previewHeight must be an even value in range 120..2160");
        }
        if (previewFps < 1 || previewFps > 30) {
            throw new IllegalArgumentException("previewFps must be in range 1..30");
        }
        if (maxPayloadBytes < 1 || maxPayloadBytes > 65_535) {
            throw new IllegalArgumentException("maxPayloadBytes must be in range 1..65535");
        }
        trip = trip == null ? TripConfig.defaults() : trip;
        driver = driver == null ? DriverConfig.defaults() : driver;
        alarm = alarm == null ? AlarmConfig.defaults() : alarm;
        simFormat = simFormat == null ? Jt1078SimFormat.STANDARD : simFormat;
        recording = recording == null ? RecordingConfig.defaults() : recording;
        fleet = fleet == null ? FleetConfig.defaults() : fleet;
        terminalManagement = terminalManagement == null
                ? TerminalManagementConfig.defaults() : terminalManagement;
        waybill = waybill == null ? WaybillConfig.defaults() : waybill;
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static SimulatorConfig fromJson(
            @JsonProperty("signalHost") String signalHost,
            @JsonProperty("signalPort") Integer signalPort,
            @JsonProperty("version") Jt808Version version,
            @JsonProperty("mobileNo") String mobileNo,
            @JsonProperty("deviceId") String deviceId,
            @JsonProperty("channel") Integer channel,
            @JsonProperty("registration") RegistrationConfig registration,
            @JsonProperty("ffmpegPath") String ffmpegPath,
            @JsonProperty("cameraName") String cameraName,
            @JsonProperty("microphoneName") String microphoneName,
            @JsonProperty("mainProfile") VideoProfile mainProfile,
            @JsonProperty("subProfile") VideoProfile subProfile,
            @JsonProperty("previewWidth") Integer previewWidth,
            @JsonProperty("previewHeight") Integer previewHeight,
            @JsonProperty("previewFps") Integer previewFps,
            @JsonProperty("maxPayloadBytes") Integer maxPayloadBytes,
            @JsonProperty("trip") TripConfig trip,
            @JsonProperty("driver") DriverConfig driver,
            @JsonProperty("alarm") AlarmConfig alarm,
            @JsonProperty("simFormat") Jt1078SimFormat simFormat,
            @JsonProperty("recording") RecordingConfig recording,
            @JsonProperty("fleet") FleetConfig fleet,
            @JsonProperty("terminalManagement") TerminalManagementConfig terminalManagement,
            @JsonProperty("waybill") WaybillConfig waybill) {
        SimulatorConfig defaults = defaults();
        return new SimulatorConfig(
                signalHost == null ? defaults.signalHost() : signalHost,
                signalPort == null ? defaults.signalPort() : signalPort,
                version == null ? defaults.version() : version,
                mobileNo == null ? defaults.mobileNo() : mobileNo,
                deviceId == null ? defaults.deviceId() : deviceId,
                channel == null ? defaults.channel() : channel,
                registration == null ? defaults.registration() : registration,
                ffmpegPath == null ? defaults.ffmpegPath() : ffmpegPath,
                cameraName == null ? defaults.cameraName() : cameraName,
                microphoneName == null ? defaults.microphoneName() : microphoneName,
                mainProfile == null ? defaults.mainProfile() : mainProfile,
                subProfile == null ? defaults.subProfile() : subProfile,
                previewWidth == null ? defaults.previewWidth() : previewWidth,
                previewHeight == null ? defaults.previewHeight() : previewHeight,
                previewFps == null ? defaults.previewFps() : previewFps,
                maxPayloadBytes == null ? defaults.maxPayloadBytes() : maxPayloadBytes,
                trip == null ? defaults.trip() : trip,
                driver == null ? defaults.driver() : driver,
                alarm == null ? defaults.alarm() : alarm,
                simFormat == null ? defaults.simFormat() : simFormat,
                recording == null ? defaults.recording() : recording,
                fleet == null ? defaults.fleet() : fleet,
                terminalManagement == null ? defaults.terminalManagement() : terminalManagement,
                waybill == null ? defaults.waybill() : waybill);
    }

    public static SimulatorConfig defaults() {
        return new SimulatorConfig(
                "127.0.0.1", DEFAULT_SIGNAL_PORT, Jt808Version.V2013,
                "138000000000", "1380000", 1, RegistrationConfig.defaults(), "", "", "",
                VideoProfile.defaultMain(), VideoProfile.defaultSub(), 640, 360, 5,
                DEFAULT_MAX_PAYLOAD_BYTES, TripConfig.defaults(), DriverConfig.defaults(),
                AlarmConfig.defaults(), Jt1078SimFormat.STANDARD, RecordingConfig.defaults(),
                FleetConfig.defaults(), TerminalManagementConfig.defaults(), WaybillConfig.defaults());
    }

    public SimulatorConfig withAlarm(AlarmConfig nextAlarm) {
        return new SimulatorConfig(signalHost, signalPort, version, mobileNo, deviceId, channel,
                registration, ffmpegPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip, driver, nextAlarm,
                simFormat, recording, fleet, terminalManagement, waybill);
    }

    public SimulatorConfig withDriver(DriverConfig nextDriver) {
        return new SimulatorConfig(signalHost, signalPort, version, mobileNo, deviceId, channel,
                registration, ffmpegPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip, nextDriver, alarm,
                simFormat, recording, fleet, terminalManagement, waybill);
    }

    public SimulatorConfig withTerminalManagement(TerminalManagementConfig management) {
        return new SimulatorConfig(signalHost, signalPort, version, mobileNo, deviceId, channel,
                registration, ffmpegPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip, driver, alarm,
                simFormat, recording, fleet, management, waybill);
    }

    public SimulatorConfig withWaybill(WaybillConfig nextWaybill) {
        return new SimulatorConfig(signalHost, signalPort, version, mobileNo, deviceId, channel,
                registration, ffmpegPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip, driver, alarm,
                simFormat, recording, fleet, terminalManagement, nextWaybill);
    }

    public SimulatorConfig withFfmpegPath(String resolvedPath) {
        return new SimulatorConfig(signalHost, signalPort, version, mobileNo, deviceId, channel,
                registration, resolvedPath, cameraName, microphoneName, mainProfile, subProfile,
                previewWidth, previewHeight, previewFps, maxPayloadBytes, trip, driver, alarm,
                simFormat, recording, fleet, terminalManagement, waybill);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, String name) {
        return Objects.requireNonNull(value, name).trim();
    }

    private static String requireDigits(String value, String name, int minimum, int maximum) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.length() < minimum || normalized.length() > maximum
                || !normalized.chars().allMatch(Character::isDigit)) {
            String expected = minimum == maximum ? Integer.toString(minimum) : minimum + ".." + maximum;
            throw new IllegalArgumentException(name + " must contain " + expected + " digits");
        }
        return normalized;
    }
}
