package io.github.jtplatform.simulator.config;

import java.math.BigInteger;
import java.util.Objects;

/** 从单车模板派生车队成员，所有成员身份在启动前一次性校验。 */
public final class FleetIdentityDeriver {
    private FleetIdentityDeriver() {
    }

    public static SimulatorConfig derive(SimulatorConfig template, FleetConfig fleet, int index) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(fleet, "fleet");
        if (index < 0 || index >= fleet.vehicleCount()) {
            throw new IndexOutOfBoundsException("fleet index must be in range 0.."
                    + (fleet.vehicleCount() - 1));
        }
        String deviceId = fleet.incrementDeviceId()
                ? incrementDigits(template.deviceId(), index, "deviceId") : template.deviceId();
        String mobileNo = fleet.incrementMobileNo()
                ? incrementDigits(template.mobileNo(), index, "mobileNo") : template.mobileNo();
        RegistrationConfig registration = template.registration();
        String plateNo = fleet.incrementPlateNo()
                ? incrementPlateSuffix(registration.plateNo(), index) : registration.plateNo();
        registration = new RegistrationConfig(
                registration.provinceId(), registration.cityId(), registration.makerId(),
                registration.deviceModel(), registration.plateColor(), plateNo,
                registration.imei(), registration.softwareVersion());
        return new SimulatorConfig(
                template.signalHost(), template.signalPort(), template.version(), mobileNo,
                deviceId, template.channel(), registration, template.ffmpegPath(),
                template.cameraName(), template.microphoneName(), template.mainProfile(),
                template.subProfile(), template.previewWidth(), template.previewHeight(),
                template.previewFps(), template.maxPayloadBytes(), template.trip(),
                template.driver(), template.alarm(), template.simFormat(), template.recording());
    }

    private static String incrementDigits(String value, int increment, String field) {
        try {
            BigInteger result = new BigInteger(value).add(BigInteger.valueOf(increment));
            BigInteger max = BigInteger.TEN.pow(value.length());
            if (result.signum() < 0 || result.compareTo(max) >= 0) {
                throw new IllegalArgumentException(field + " increment overflows its fixed width");
            }
            return String.format("%0" + value.length() + "d", result);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be a fixed-width decimal number", failure);
        }
    }

    private static String incrementPlateSuffix(String plate, int increment) {
        int end = plate.length();
        while (end > 0 && Character.isDigit(plate.charAt(end - 1))) {
            end--;
        }
        if (end == plate.length()) {
            throw new IllegalArgumentException("plateNo must end with digits to increment");
        }
        String prefix = plate.substring(0, end);
        String suffix = plate.substring(end);
        return prefix + incrementDigits(suffix, increment, "plateNo suffix");
    }
}
