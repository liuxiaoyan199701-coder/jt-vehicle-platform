package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.Jt808Version;
import io.github.jtplatform.simulator.config.RegistrationConfig;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.AlarmConfig;
import io.github.jtplatform.simulator.config.DriverConfig;
import io.github.jtplatform.simulator.config.TripConfig;
import org.yzh.protocol.t1078.codec.Jt1078SimFormat;
import io.github.jtplatform.simulator.config.VideoProfile;
import io.github.jtplatform.simulator.trip.CoordinateTransform;
import io.github.jtplatform.simulator.trip.GeoPoint;
import io.github.jtplatform.simulator.trip.Route;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record SimulatorFormData(
        String signalHost,
        String signalPort,
        Jt808Version version,
        String mobileNo,
        String deviceId,
        String channel,
        String provinceId,
        String cityId,
        String makerId,
        String deviceModel,
        String plateColor,
        String plateNo,
        String imei,
        String softwareVersion,
        String ffmpegPath,
        String cameraName,
        String microphoneName,
        ProfileFormData mainProfile,
        ProfileFormData subProfile,
        String previewWidth,
        String previewHeight,
        String previewFrameRate,
        String maxPayloadBytes,
        TripFormData trip,
        Jt1078SimFormat simFormat,
        String driverName,
        String driverIdCard,
        String driverLicenseNo,
        String driverInstitution,
        String driverValidPeriod,
        String overspeedKph) {

    /** 兼容新增表单字段前的测试/调用方构造方式。 */
    public SimulatorFormData(
            String signalHost, String signalPort, Jt808Version version, String mobileNo,
            String deviceId, String channel, String provinceId, String cityId,
            String makerId, String deviceModel, String plateColor, String plateNo,
            String imei, String softwareVersion, String ffmpegPath, String cameraName,
            String microphoneName, ProfileFormData mainProfile, ProfileFormData subProfile,
            String previewWidth, String previewHeight, String previewFrameRate,
            String maxPayloadBytes, TripFormData trip) {
        this(signalHost, signalPort, version, mobileNo, deviceId, channel, provinceId, cityId,
                makerId, deviceModel, plateColor, plateNo, imei, softwareVersion, ffmpegPath,
                cameraName, microphoneName, mainProfile, subProfile, previewWidth, previewHeight,
                previewFrameRate, maxPayloadBytes, trip, Jt1078SimFormat.STANDARD,
                "", "", "", "", "", Integer.toString(AlarmConfig.DEFAULT_OVERSPEED_KPH));
    }

    public SimulatorFormData {
        signalHost = normalize(signalHost);
        signalPort = normalize(signalPort);
        mobileNo = normalize(mobileNo);
        deviceId = normalize(deviceId);
        channel = normalize(channel);
        provinceId = normalize(provinceId);
        cityId = normalize(cityId);
        makerId = normalize(makerId);
        deviceModel = normalize(deviceModel);
        plateColor = normalize(plateColor);
        plateNo = normalize(plateNo);
        imei = normalize(imei);
        softwareVersion = normalize(softwareVersion);
        ffmpegPath = normalize(ffmpegPath);
        cameraName = normalize(cameraName);
        microphoneName = normalize(microphoneName);
        mainProfile = Objects.requireNonNull(mainProfile, "mainProfile");
        subProfile = Objects.requireNonNull(subProfile, "subProfile");
        previewWidth = normalize(previewWidth);
        previewHeight = normalize(previewHeight);
        previewFrameRate = normalize(previewFrameRate);
        maxPayloadBytes = normalize(maxPayloadBytes);
        trip = trip == null ? TripFormData.from(TripConfig.defaults()) : trip;
        simFormat = simFormat == null ? Jt1078SimFormat.STANDARD : simFormat;
        driverName = normalize(driverName);
        driverIdCard = normalize(driverIdCard);
        driverLicenseNo = normalize(driverLicenseNo);
        driverInstitution = normalize(driverInstitution);
        driverValidPeriod = normalize(driverValidPeriod);
        overspeedKph = normalize(overspeedKph);
    }

    public static SimulatorFormData from(SimulatorConfig config) {
        Objects.requireNonNull(config, "config");
        RegistrationConfig registration = config.registration();
        return new SimulatorFormData(
                config.signalHost(),
                Integer.toString(config.signalPort()),
                config.version(),
                config.mobileNo(),
                config.deviceId(),
                Integer.toString(config.channel()),
                Integer.toString(registration.provinceId()),
                Integer.toString(registration.cityId()),
                registration.makerId(),
                registration.deviceModel(),
                Integer.toString(registration.plateColor()),
                registration.plateNo(),
                registration.imei(),
                registration.softwareVersion(),
                config.ffmpegPath(),
                config.cameraName(),
                config.microphoneName(),
                ProfileFormData.from(config.mainProfile()),
                ProfileFormData.from(config.subProfile()),
                Integer.toString(config.previewWidth()),
                Integer.toString(config.previewHeight()),
                Integer.toString(config.previewFps()),
                Integer.toString(config.maxPayloadBytes()),
                TripFormData.from(config.trip()), config.simFormat(),
                config.driver().name(), config.driver().idCard(), config.driver().licenseNo(),
                config.driver().institution(), config.driver().licenseValidPeriod(),
                Integer.toString(config.alarm().overspeedKph()));
    }

    public ConfigValidation validate() {
        EnumMap<ConfigField, String> errors = new EnumMap<>(ConfigField.class);
        String checkedHost = requireText(signalHost, ConfigField.SIGNAL_HOST,
                "请输入信令服务器地址", errors);
        Integer checkedPort = integer(signalPort, ConfigField.SIGNAL_PORT,
                "信令端口", 1, 65_535, errors);
        if (version == null) {
            errors.put(ConfigField.VERSION, "请选择 JT/T 808 版本");
        }
        int mobileLength = version == null ? 12 : version.mobileNumberLength();
        String checkedMobile = mobileNoDigits(mobileNo, ConfigField.MOBILE_NO, mobileLength, errors);
        String checkedDeviceId = digits(deviceId, ConfigField.DEVICE_ID,
                "设备 ID", 1, 12, errors);
        Integer checkedChannel = integer(channel, ConfigField.CHANNEL,
                "逻辑通道", 1, 255, errors);

        Integer checkedProvince = integer(provinceId, ConfigField.PROVINCE_ID,
                "省域 ID", 0, 65_535, errors);
        Integer checkedCity = integer(cityId, ConfigField.CITY_ID,
                "市县域 ID", 0, 65_535, errors);
        String checkedMaker = length(makerId, ConfigField.MAKER_ID,
                "制造商 ID", 1, 11, errors);
        String checkedModel = length(deviceModel, ConfigField.DEVICE_MODEL,
                "终端型号", 1, 30, errors);
        Integer checkedPlateColor = integer(plateColor, ConfigField.PLATE_COLOR,
                "车牌颜色", 0, 255, errors);
        String checkedPlate = length(plateNo, ConfigField.PLATE_NO,
                "车牌号", 1, 64, errors);
        String checkedImei = digits(imei, ConfigField.IMEI,
                "IMEI", 15, 15, errors);
        String checkedSoftware = length(softwareVersion, ConfigField.SOFTWARE_VERSION,
                "软件版本", 1, 20, errors);
        validateFfmpegPath(ffmpegPath, errors);

        VideoProfile checkedMain = profile(mainProfile, true, errors);
        VideoProfile checkedSub = profile(subProfile, false, errors);
        Integer checkedPreviewWidth = evenInteger(previewWidth, ConfigField.PREVIEW_WIDTH,
                "预览宽度", 160, 3_840, errors);
        Integer checkedPreviewHeight = evenInteger(previewHeight, ConfigField.PREVIEW_HEIGHT,
                "预览高度", 120, 2_160, errors);
        Integer checkedPreviewFps = integer(previewFrameRate, ConfigField.PREVIEW_FRAME_RATE,
                "预览帧率", 1, 30, errors);
        Integer checkedPayload = integer(maxPayloadBytes, ConfigField.MAX_PAYLOAD_BYTES,
                "单片载荷", 1, 65_535, errors);
        Integer checkedOverspeed = integer(overspeedKph, ConfigField.OVERSPEED_KPH,
                "超速联动速度", 1, 300, errors);

        TripConfig checkedTrip = trip(errors);

        if (!errors.isEmpty()) {
            return new ConfigValidation(Optional.empty(), errors);
        }

        try {
            RegistrationConfig registration = new RegistrationConfig(
                    checkedProvince,
                    checkedCity,
                    checkedMaker,
                    checkedModel,
                    checkedPlateColor,
                    checkedPlate,
                    checkedImei,
                    checkedSoftware);
            SimulatorConfig config = new SimulatorConfig(
                    checkedHost,
                    checkedPort,
                    version,
                    checkedMobile,
                    checkedDeviceId,
                    checkedChannel,
                    registration,
                    ffmpegPath,
                    cameraName,
                    microphoneName,
                    checkedMain,
                    checkedSub,
                    checkedPreviewWidth,
                    checkedPreviewHeight,
                    checkedPreviewFps,
                    checkedPayload,
                    checkedTrip,
                    new DriverConfig(driverName, driverIdCard, driverLicenseNo,
                            driverInstitution, driverValidPeriod),
                    new AlarmConfig(0, checkedOverspeed),
                    simFormat);
            return new ConfigValidation(Optional.of(config), Map.of());
        } catch (IllegalArgumentException unexpected) {
            return new ConfigValidation(
                    Optional.empty(), Map.of(ConfigField.GENERAL, unexpected.getMessage()));
        }
    }

    /**
     * 校验「行程」页。
     *
     * <p>起终点四项要么全空（使用内置路线），要么成对填写。半个坐标是无法使用的，
     * 而且用户多半只是漏填了一格——就近提示比默默忽略有用得多。
     */
    private TripConfig trip(EnumMap<ConfigField, String> errors) {
        Double originLat = optionalDecimal(trip.originLat(), ConfigField.TRIP_ORIGIN_LAT,
                "起点纬度", -90.0D, 90.0D, errors);
        Double originLng = optionalDecimal(trip.originLng(), ConfigField.TRIP_ORIGIN_LNG,
                "起点经度", -180.0D, 180.0D, errors);
        Double destinationLat = optionalDecimal(trip.destinationLat(),
                ConfigField.TRIP_DESTINATION_LAT, "终点纬度", -90.0D, 90.0D, errors);
        Double destinationLng = optionalDecimal(trip.destinationLng(),
                ConfigField.TRIP_DESTINATION_LNG, "终点经度", -180.0D, 180.0D, errors);

        requirePairedCoordinates(trip.originLat(), trip.originLng(),
                ConfigField.TRIP_ORIGIN_LAT, ConfigField.TRIP_ORIGIN_LNG, "起点", errors);
        requirePairedCoordinates(trip.destinationLat(), trip.destinationLng(),
                ConfigField.TRIP_DESTINATION_LAT, ConfigField.TRIP_DESTINATION_LNG,
                "终点", errors);

        if (originLat != null && originLng != null
                && destinationLat != null && destinationLng != null) {
            double separation = CoordinateTransform.distanceMeters(
                    new GeoPoint(originLat, originLng),
                    new GeoPoint(destinationLat, destinationLng));
            if (separation < Route.MIN_LENGTH_METERS) {
                errors.put(ConfigField.TRIP_DESTINATION_LAT,
                        "终点距起点仅 %.0f 米，请拉开至 %.0f 米以上，或将起终点都留空以使用内置路线"
                                .formatted(separation, Route.MIN_LENGTH_METERS));
            }
        }

        Double speed = decimal(trip.speedKph(), ConfigField.TRIP_SPEED, "行驶速度",
                TripConfig.MIN_SPEED_KPH, TripConfig.MAX_SPEED_KPH, errors);
        Integer interval = integer(trip.reportIntervalSeconds(),
                ConfigField.TRIP_REPORT_INTERVAL, "上报间隔",
                TripConfig.MIN_REPORT_INTERVAL_SECONDS, TripConfig.MAX_REPORT_INTERVAL_SECONDS,
                errors);
        if (speed == null || interval == null) {
            return null;
        }
        return new TripConfig(trip.autoStart(), trip.amapKey(), originLat, originLng,
                destinationLat, destinationLng, speed, interval, trip.roundTrip());
    }

    private static void requirePairedCoordinates(
            String latitude,
            String longitude,
            ConfigField latitudeField,
            ConfigField longitudeField,
            String label,
            EnumMap<ConfigField, String> errors) {
        if (latitude.isEmpty() == longitude.isEmpty()) {
            return;
        }
        ConfigField missing = latitude.isEmpty() ? latitudeField : longitudeField;
        String missingLabel = latitude.isEmpty() ? "纬度" : "经度";
        errors.putIfAbsent(missing,
                "还需要填写%s%s，或把%s的经纬度都清空".formatted(label, missingLabel, label));
    }

    /**
     * 解析可留空的小数。空串代表「未指定」，不是错误。
     *
     * <p>必须显式挡住无穷与非数：{@link Double#parseDouble} 对字符串 {@code "Infinity"} 与
     * {@code "NaN"} 都会**解析成功**，而它们一旦进入坐标运算就会把整条路线变成 NaN。
     */
    private static Double optionalDecimal(
            String value,
            ConfigField field,
            String label,
            double minimum,
            double maximum,
            EnumMap<ConfigField, String> errors) {
        if (value.isEmpty()) {
            return null;
        }
        return decimal(value, field, label, minimum, maximum, errors);
    }

    private static Double decimal(
            String value,
            ConfigField field,
            String label,
            double minimum,
            double maximum,
            EnumMap<ConfigField, String> errors) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                errors.put(field, label + "必须为数字");
                return null;
            }
            if (parsed < minimum || parsed > maximum) {
                errors.put(field, "%s必须在 %s～%s 范围内".formatted(
                        label, trimZero(minimum), trimZero(maximum)));
                return null;
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            errors.put(field, label + "必须为数字");
            return null;
        }
    }

    private static String trimZero(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static VideoProfile profile(
            ProfileFormData profile,
            boolean main,
            EnumMap<ConfigField, String> errors) {
        ConfigField widthField = main ? ConfigField.MAIN_WIDTH : ConfigField.SUB_WIDTH;
        ConfigField heightField = main ? ConfigField.MAIN_HEIGHT : ConfigField.SUB_HEIGHT;
        ConfigField frameRateField = main
                ? ConfigField.MAIN_FRAME_RATE : ConfigField.SUB_FRAME_RATE;
        ConfigField bitrateField = main ? ConfigField.MAIN_BITRATE : ConfigField.SUB_BITRATE;
        ConfigField gopField = main ? ConfigField.MAIN_GOP : ConfigField.SUB_GOP;
        String prefix = main ? "主码流" : "子码流";

        Integer width = evenInteger(profile.width(), widthField,
                prefix + "宽度", 160, 7_680, errors);
        Integer height = evenInteger(profile.height(), heightField,
                prefix + "高度", 120, 4_320, errors);
        Integer frameRate = integer(profile.frameRate(), frameRateField,
                prefix + "帧率", 1, 60, errors);
        Integer bitrate = integer(profile.bitrateKbps(), bitrateField,
                prefix + "码率", 64, 50_000, errors);
        Integer gop = integer(profile.gopSeconds(), gopField,
                prefix + " GOP", 1, 10, errors);
        if (width == null || height == null || frameRate == null
                || bitrate == null || gop == null) {
            return null;
        }
        return new VideoProfile(width, height, frameRate, bitrate, gop);
    }

    private static String requireText(
            String value,
            ConfigField field,
            String message,
            EnumMap<ConfigField, String> errors) {
        if (value.isBlank()) {
            errors.put(field, message);
        }
        return value;
    }

    private static String length(
            String value,
            ConfigField field,
            String label,
            int minimum,
            int maximum,
            EnumMap<ConfigField, String> errors) {
        if (value.length() < minimum || value.length() > maximum) {
            errors.put(field, label + "长度必须为 " + minimum + "～" + maximum + " 个字符");
        }
        return value;
    }

    /**
     * 终端手机号是定宽 BCD 字段（2013 版 12 位、2019 版 20 位）。用户切换协议版本后
     * 沿用短号码时自动左侧补零——前导零是合法的填充位，不应要求用户手动补全。
     * 只有超长或非数字才报错。
     */
    private static String mobileNoDigits(
            String value,
            ConfigField field,
            int requiredLength,
            EnumMap<ConfigField, String> errors) {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            errors.put(field, "手机号必须为 " + requiredLength + " 位数字");
            return value;
        }
        if (value.length() > requiredLength) {
            errors.put(field, "手机号最长 " + requiredLength + " 位");
            return value;
        }
        return "0".repeat(requiredLength - value.length()) + value;
    }

    private static String digits(
            String value,
            ConfigField field,
            String label,
            int minimum,
            int maximum,
            EnumMap<ConfigField, String> errors) {
        if (value.length() < minimum || value.length() > maximum
                || !value.chars().allMatch(Character::isDigit)) {
            String expected = minimum == maximum
                    ? Integer.toString(minimum) : minimum + "～" + maximum;
            errors.put(field, label + "必须为 " + expected + " 位数字");
        }
        return value;
    }

    private static Integer evenInteger(
            String value,
            ConfigField field,
            String label,
            int minimum,
            int maximum,
            EnumMap<ConfigField, String> errors) {
        Integer parsed = integer(value, field, label, minimum, maximum, errors);
        if (parsed != null && (parsed & 1) != 0) {
            errors.put(field, label + "必须为偶数");
            return null;
        }
        return parsed;
    }

    private static void validateFfmpegPath(
            String value,
            EnumMap<ConfigField, String> errors) {
        if (value.isBlank()) {
            return;
        }
        try {
            if (!Path.of(value).isAbsolute()) {
                errors.put(ConfigField.FFMPEG_PATH, "FFmpeg 路径必须为绝对路径");
            }
        } catch (InvalidPathException invalid) {
            errors.put(ConfigField.FFMPEG_PATH, "FFmpeg 路径格式无效");
        }
    }

    private static Integer integer(
            String value,
            ConfigField field,
            String label,
            int minimum,
            int maximum,
            EnumMap<ConfigField, String> errors) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                errors.put(field, label + "必须在 " + minimum + "～" + maximum + " 范围内");
                return null;
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            errors.put(field, label + "必须为整数");
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
