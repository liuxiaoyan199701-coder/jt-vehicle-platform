package io.github.jtplatform.simulator.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.Jt808Version;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import org.junit.jupiter.api.Test;

class SimulatorFormDataTest {
    @Test
    void roundTripsAValidConfiguration() {
        SimulatorConfig expected = SimulatorConfig.defaults();

        ConfigValidation validation = SimulatorFormData.from(expected).validate();

        assertTrue(validation.valid());
        assertEquals(expected, validation.config().orElseThrow());
        assertTrue(validation.errors().isEmpty());
    }

    @Test
    void reportsEveryInvalidIdentityField() {
        SimulatorFormData defaults = SimulatorFormData.from(SimulatorConfig.defaults());
        SimulatorFormData invalid = copyIdentity(
                defaults, Jt808Version.V2013, "123", "device", "256");

        ConfigValidation validation = invalid.validate();

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(ConfigField.MOBILE_NO).contains("12 位数字"));
        assertTrue(validation.errors().get(ConfigField.DEVICE_ID).contains("1～12 位数字"));
        assertTrue(validation.errors().get(ConfigField.CHANNEL).contains("1～255"));
    }

    @Test
    void appliesTheSelectedJt808MobileNumberLength() {
        SimulatorFormData defaults = SimulatorFormData.from(SimulatorConfig.defaults());
        SimulatorFormData invalid2019 = copyIdentity(
                defaults, Jt808Version.V2019, "138000000000", "1", "1");

        ConfigValidation validation = invalid2019.validate();

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(ConfigField.MOBILE_NO).contains("20 位数字"));
    }

    @Test
    void mapsProfileAndPreviewFailuresToTheirControls() {
        SimulatorFormData defaults = SimulatorFormData.from(SimulatorConfig.defaults());
        SimulatorFormData invalid = new SimulatorFormData(
                defaults.signalHost(), defaults.signalPort(), defaults.version(),
                defaults.mobileNo(), defaults.deviceId(), defaults.channel(),
                defaults.provinceId(), defaults.cityId(), defaults.makerId(),
                defaults.deviceModel(), defaults.plateColor(), defaults.plateNo(),
                defaults.imei(), defaults.softwareVersion(), defaults.ffmpegPath(),
                defaults.cameraName(), defaults.microphoneName(),
                new ProfileFormData("1279", "720", "0", "63", "11"),
                defaults.subProfile(), "639", "119", "31", "0");

        ConfigValidation validation = invalid.validate();

        assertFalse(validation.valid());
        assertEquals("主码流宽度必须为偶数", validation.errors().get(ConfigField.MAIN_WIDTH));
        assertTrue(validation.errors().containsKey(ConfigField.MAIN_FRAME_RATE));
        assertTrue(validation.errors().containsKey(ConfigField.MAIN_BITRATE));
        assertTrue(validation.errors().containsKey(ConfigField.MAIN_GOP));
        assertTrue(validation.errors().containsKey(ConfigField.PREVIEW_WIDTH));
        assertTrue(validation.errors().containsKey(ConfigField.PREVIEW_HEIGHT));
        assertTrue(validation.errors().containsKey(ConfigField.PREVIEW_FRAME_RATE));
        assertTrue(validation.errors().containsKey(ConfigField.MAX_PAYLOAD_BYTES));
    }

    @Test
    void requiresAConfiguredFfmpegPathToBeAbsolute() {
        SimulatorFormData defaults = SimulatorFormData.from(SimulatorConfig.defaults());
        SimulatorFormData invalid = new SimulatorFormData(
                defaults.signalHost(), defaults.signalPort(), defaults.version(),
                defaults.mobileNo(), defaults.deviceId(), defaults.channel(),
                defaults.provinceId(), defaults.cityId(), defaults.makerId(),
                defaults.deviceModel(), defaults.plateColor(), defaults.plateNo(),
                defaults.imei(), defaults.softwareVersion(), "tools/ffmpeg.exe",
                defaults.cameraName(), defaults.microphoneName(), defaults.mainProfile(),
                defaults.subProfile(), defaults.previewWidth(), defaults.previewHeight(),
                defaults.previewFrameRate(), defaults.maxPayloadBytes());

        ConfigValidation validation = invalid.validate();

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(ConfigField.FFMPEG_PATH).contains("绝对路径"));
    }

    private static SimulatorFormData copyIdentity(
            SimulatorFormData source,
            Jt808Version version,
            String mobileNo,
            String deviceId,
            String channel) {
        return new SimulatorFormData(
                source.signalHost(), source.signalPort(), version, mobileNo, deviceId, channel,
                source.provinceId(), source.cityId(), source.makerId(), source.deviceModel(),
                source.plateColor(), source.plateNo(), source.imei(), source.softwareVersion(),
                source.ffmpegPath(), source.cameraName(), source.microphoneName(),
                source.mainProfile(), source.subProfile(), source.previewWidth(),
                source.previewHeight(), source.previewFrameRate(), source.maxPayloadBytes());
    }
}
