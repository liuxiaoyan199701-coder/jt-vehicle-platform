package io.github.jtplatform.simulator.config;

import java.util.Objects;

public record RegistrationConfig(
        int provinceId,
        int cityId,
        String makerId,
        String deviceModel,
        int plateColor,
        String plateNo,
        String imei,
        String softwareVersion) {

    public RegistrationConfig {
        if (provinceId < 0 || provinceId > 65_535) {
            throw new IllegalArgumentException("provinceId must be in range 0..65535");
        }
        if (cityId < 0 || cityId > 65_535) {
            throw new IllegalArgumentException("cityId must be in range 0..65535");
        }
        makerId = requireLength(makerId, "makerId", 1, 11);
        deviceModel = requireLength(deviceModel, "deviceModel", 1, 30);
        if (plateColor < 0 || plateColor > 255) {
            throw new IllegalArgumentException("plateColor must be in range 0..255");
        }
        plateNo = requireLength(plateNo, "plateNo", 1, 64);
        imei = requireDigits(imei, "imei", 15);
        softwareVersion = requireLength(softwareVersion, "softwareVersion", 1, 20);
    }

    public static RegistrationConfig defaults() {
        return new RegistrationConfig(
                31,
                100,
                "JT",
                "SIMULATOR",
                1,
                "TEST001",
                "000000000000000",
                "0.1.0");
    }

    private static String requireLength(String value, String name, int minimum, int maximum) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.length() < minimum || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " length must be in range " + minimum + ".." + maximum);
        }
        return normalized;
    }

    private static String requireDigits(String value, String name, int length) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.length() != length || !normalized.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(name + " must contain exactly " + length + " digits");
        }
        return normalized;
    }
}
