package io.github.jtplatform.simulator.config;

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
        int maxPayloadBytes) {

    public static final int DEFAULT_SIGNAL_PORT = 7_100;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 1_400;

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
    }

    public static SimulatorConfig defaults() {
        return new SimulatorConfig(
                "127.0.0.1",
                DEFAULT_SIGNAL_PORT,
                Jt808Version.V2013,
                "138000000000",
                "1380000",
                1,
                RegistrationConfig.defaults(),
                "",
                "",
                "",
                VideoProfile.defaultMain(),
                VideoProfile.defaultSub(),
                640,
                360,
                5,
                DEFAULT_MAX_PAYLOAD_BYTES);
    }

    public SimulatorConfig withFfmpegPath(String resolvedPath) {
        return new SimulatorConfig(
                signalHost, signalPort, version, mobileNo, deviceId, channel,
                registration, resolvedPath, cameraName, microphoneName,
                mainProfile, subProfile, previewWidth, previewHeight, previewFps,
                maxPayloadBytes);
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
