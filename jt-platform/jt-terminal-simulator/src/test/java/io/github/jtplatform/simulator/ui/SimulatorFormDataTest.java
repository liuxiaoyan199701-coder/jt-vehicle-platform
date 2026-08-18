package io.github.jtplatform.simulator.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.Jt808Version;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.TripConfig;
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
                defaults, Jt808Version.V2013, "12a", "device", "256");

        ConfigValidation validation = invalid.validate();

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(ConfigField.MOBILE_NO).contains("12 位数字"));
        assertTrue(validation.errors().get(ConfigField.DEVICE_ID).contains("1～12 位数字"));
        assertTrue(validation.errors().get(ConfigField.CHANNEL).contains("1～255"));
    }

    @Test
    void padsShortMobileNumberToTheSelectedJt808Length() {
        SimulatorFormData defaults = SimulatorFormData.from(SimulatorConfig.defaults());
        SimulatorFormData padded2019 = copyIdentity(
                defaults, Jt808Version.V2019, "138000000000", "1", "1");

        ConfigValidation validation = padded2019.validate();

        assertTrue(validation.valid());
        SimulatorConfig config = validation.config().orElseThrow();
        assertEquals(20, config.mobileNo().length());
        assertEquals("00000000138000000000", config.mobileNo());
    }

    @Test
    void rejectsMobileNumberLongerThanTheSelectedJt808Length() {
        SimulatorFormData defaults = SimulatorFormData.from(SimulatorConfig.defaults());
        SimulatorFormData tooLong = copyIdentity(
                defaults, Jt808Version.V2013, "13800000000012345678", "1", "1");

        ConfigValidation validation = tooLong.validate();

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(ConfigField.MOBILE_NO).contains("最长 12 位"));
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
                defaults.subProfile(), "639", "119", "31", "0", defaults.trip());

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
                defaults.previewFrameRate(), defaults.maxPayloadBytes(), defaults.trip());

        ConfigValidation validation = invalid.validate();

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(ConfigField.FFMPEG_PATH).contains("绝对路径"));
    }

    @Test
    void acceptsATripWithNoEndpointsAtAll() {
        // 四项全空是「使用内置路线」，是本页面唯一一处「空是合法值」的地方。
        ConfigValidation validation = withTrip(trip("", "", "", "")).validate();

        assertTrue(validation.valid());
        assertFalse(validation.config().orElseThrow().trip().hasOrigin());
        assertFalse(validation.config().orElseThrow().trip().hasDestination());
    }

    @Test
    void asksForTheMissingHalfOfAPartiallyEnteredEndpoint() {
        ConfigValidation validation = withTrip(trip("31.23", "", "", "")).validate();

        assertFalse(validation.valid());
        String message = validation.errors().get(ConfigField.TRIP_ORIGIN_LNG);
        assertTrue(message.contains("起点经度"), message);
        // 提示必须给出可执行的下一步，而不只是陈述错误。
        assertTrue(message.contains("清空"), message);
    }

    @Test
    void rejectsCoordinatesOutsideTheGlobe() {
        ConfigValidation validation =
                withTrip(trip("91", "181", "-91", "-181")).validate();

        assertFalse(validation.valid());
        assertTrue(validation.errors().containsKey(ConfigField.TRIP_ORIGIN_LAT));
        assertTrue(validation.errors().containsKey(ConfigField.TRIP_ORIGIN_LNG));
        assertTrue(validation.errors().containsKey(ConfigField.TRIP_DESTINATION_LAT));
        assertTrue(validation.errors().containsKey(ConfigField.TRIP_DESTINATION_LNG));
    }

    /**
     * {@code parseDouble} 对 {@code "Infinity"} 与 {@code "NaN"} 都会解析成功，
     * 而它们一旦进入坐标运算就会把整条路线变成 NaN——必须显式挡下。
     */
    @Test
    void rejectsTextAndNonFiniteNumbersInCoordinates() {
        ConfigValidation validation =
                withTrip(trip("abc", "Infinity", "NaN", "1e999")).validate();

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(ConfigField.TRIP_ORIGIN_LAT).contains("必须为数字"));
        assertTrue(validation.errors().get(ConfigField.TRIP_ORIGIN_LNG).contains("必须为数字"));
        assertTrue(validation.errors().get(ConfigField.TRIP_DESTINATION_LAT).contains("必须为数字"));
        assertTrue(validation.errors().get(ConfigField.TRIP_DESTINATION_LNG).contains("必须为数字"));
    }

    @Test
    void rejectsEndpointsTooCloseTogetherAndSuggestsLeavingThemBlank() {
        // 相距约 11 米，短于可模拟的最小长度。
        ConfigValidation validation =
                withTrip(trip("31.230416", "121.473701", "31.230516", "121.473701")).validate();

        assertFalse(validation.valid());
        String message = validation.errors().get(ConfigField.TRIP_DESTINATION_LAT);
        assertTrue(message.contains("留空"), message);
    }

    @Test
    void rejectsUnusableSpeedAndReportingInterval() {
        SimulatorFormData defaults = SimulatorFormData.from(SimulatorConfig.defaults());
        TripFormData source = defaults.trip();
        SimulatorFormData invalid = withTrip(new TripFormData(
                source.autoStart(), source.amapKey(), "", "", "", "",
                "0", "0", source.roundTrip()));

        ConfigValidation validation = invalid.validate();

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(ConfigField.TRIP_SPEED).contains("范围"));
        assertTrue(validation.errors().get(ConfigField.TRIP_REPORT_INTERVAL).contains("范围"));
    }

    @Test
    void roundTripsTripSettingsThroughTheForm() {
        // 表单丢字段与配置复制丢字段一样致命：用户点一次保存，行程配置就没了。
        SimulatorConfig expected = SimulatorConfig.defaults().withFfmpegPath("");
        SimulatorFormData form = withTrip(new TripFormData(
                true, "key-1", "31.230416", "121.473701", "31.239692", "121.499809",
                "80.5", "5", false));

        TripConfig result = form.validate().config().orElseThrow().trip();

        assertTrue(result.autoStart());
        assertEquals("key-1", result.amapKey());
        assertEquals(31.230416D, result.originLat(), 1e-9D);
        assertEquals(121.499809D, result.destinationLng(), 1e-9D);
        assertEquals(80.5D, result.speedKph(), 1e-9D);
        assertEquals(5, result.reportIntervalSeconds());
        assertFalse(result.roundTrip());
        assertEquals(expected.signalHost(),
                form.validate().config().orElseThrow().signalHost());
    }

    private static TripFormData trip(
            String originLat, String originLng, String destinationLat, String destinationLng) {
        TripFormData source = SimulatorFormData.from(SimulatorConfig.defaults()).trip();
        return new TripFormData(source.autoStart(), source.amapKey(),
                originLat, originLng, destinationLat, destinationLng,
                source.speedKph(), source.reportIntervalSeconds(), source.roundTrip());
    }

    private static SimulatorFormData withTrip(TripFormData trip) {
        SimulatorFormData source = SimulatorFormData.from(SimulatorConfig.defaults());
        return new SimulatorFormData(
                source.signalHost(), source.signalPort(), source.version(), source.mobileNo(),
                source.deviceId(), source.channel(), source.provinceId(), source.cityId(),
                source.makerId(), source.deviceModel(), source.plateColor(), source.plateNo(),
                source.imei(), source.softwareVersion(), source.ffmpegPath(), source.cameraName(),
                source.microphoneName(), source.mainProfile(), source.subProfile(),
                source.previewWidth(), source.previewHeight(), source.previewFrameRate(),
                source.maxPayloadBytes(), trip);
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
                source.previewHeight(), source.previewFrameRate(), source.maxPayloadBytes(),
                source.trip());
    }
}
