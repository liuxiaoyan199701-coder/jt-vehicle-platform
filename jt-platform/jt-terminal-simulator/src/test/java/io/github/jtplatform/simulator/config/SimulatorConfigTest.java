package io.github.jtplatform.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulatorConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsMatchTheDesktopContract() {
        SimulatorConfig config = SimulatorConfig.defaults();

        assertEquals(7_100, config.signalPort());
        assertEquals(Jt808Version.V2013, config.version());
        assertEquals(new VideoProfile(1_280, 720, 25, 2_000, 2), config.mainProfile());
        assertEquals(new VideoProfile(640, 360, 15, 512, 2), config.subProfile());
        assertEquals(1_400, config.maxPayloadBytes());
    }

    @Test
    void validatesVersionSpecificMobileNumberAndMediaIdentity() {
        SimulatorConfig defaults = SimulatorConfig.defaults();

        assertThrows(IllegalArgumentException.class, () -> copyIdentity(
                defaults, Jt808Version.V2013, "12345678901", "123"));
        assertThrows(IllegalArgumentException.class, () -> copyIdentity(
                defaults, Jt808Version.V2019, "123456789012", "123"));
        assertThrows(IllegalArgumentException.class, () -> copyIdentity(
                defaults, Jt808Version.V2013, "123456789012", "device"));
        assertThrows(IllegalArgumentException.class, () -> copyIdentity(
                defaults, Jt808Version.V2013, "123456789012", "1234567890123"));
    }

    @Test
    void savesAndLoadsJsonAtomically() throws Exception {
        ConfigStore store = new ConfigStore(temporaryDirectory);
        SimulatorConfig expected = copyIdentity(
                SimulatorConfig.defaults(), Jt808Version.V2019, "12345678901234567890", "123456789012");

        store.save(expected);

        assertEquals(expected, store.load());
        assertEquals(temporaryDirectory.toAbsolutePath().resolve("config.json"), store.configFile());
    }

    @Test
    void resolvesLocalAppDataAndHasAUserHomeFallback() {
        assertEquals(Path.of("C:/Users/test/AppData/Local/JTPlatform/terminal-simulator"),
                ConfigStore.defaultDirectory(Map.of("LOCALAPPDATA", "C:/Users/test/AppData/Local"), "ignored"));
        assertEquals(Path.of("C:/Users/test/AppData/Local/JTPlatform/terminal-simulator"),
                ConfigStore.defaultDirectory(Map.of(), "C:/Users/test"));
    }

    /**
     * 升级到含行程功能的版本时，用户的 {@code config.json} 写于旧版本、没有 {@code trip} 键。
     *
     * <p>这条测试守的是整个变更里后果最严重的一处：加载若因为缺少新字段而失败，上层会整份回落到
     * 默认配置——用户的信令地址、终端号、编码器路径、码流参数会一起丢掉，而且没有任何提示。
     */
    @Test
    void loadsConfigurationFilesWrittenBeforeTripSupportExisted() throws Exception {
        Path configFile = temporaryDirectory.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "signalHost": "10.0.0.9",
                  "signalPort": 7200,
                  "version": "V2019",
                  "mobileNo": "12345678901234567890",
                  "deviceId": "654321",
                  "channel": 3,
                  "registration": {
                    "provinceId": 44, "cityId": 300, "makerId": "ACME",
                    "deviceModel": "TERM-9", "plateColor": 2, "plateNo": "粤B12345",
                    "imei": "123456789012345", "softwareVersion": "9.9.9"
                  },
                  "ffmpegPath": "C:/tools/ffmpeg.exe",
                  "cameraName": "USB Camera",
                  "microphoneName": "USB Mic",
                  "mainProfile": {
                    "width": 1920, "height": 1080, "frameRate": 30,
                    "bitrateKbps": 4000, "gopSeconds": 3
                  },
                  "subProfile": {
                    "width": 704, "height": 576, "frameRate": 12,
                    "bitrateKbps": 600, "gopSeconds": 2
                  },
                  "previewWidth": 800,
                  "previewHeight": 600,
                  "previewFps": 10,
                  "maxPayloadBytes": 1200
                }
                """);

        SimulatorConfig loaded = new ConfigStore(temporaryDirectory).load();

        // 既有配置逐项保留，一个都不能少。
        assertEquals("10.0.0.9", loaded.signalHost());
        assertEquals(7_200, loaded.signalPort());
        assertEquals(Jt808Version.V2019, loaded.version());
        assertEquals("654321", loaded.deviceId());
        assertEquals(3, loaded.channel());
        assertEquals("ACME", loaded.registration().makerId());
        assertEquals("粤B12345", loaded.registration().plateNo());
        assertEquals("C:/tools/ffmpeg.exe", loaded.ffmpegPath());
        assertEquals("USB Camera", loaded.cameraName());
        assertEquals(new VideoProfile(1_920, 1_080, 30, 4_000, 3), loaded.mainProfile());
        assertEquals(new VideoProfile(704, 576, 12, 600, 2), loaded.subProfile());
        assertEquals(1_200, loaded.maxPayloadBytes());
        // 缺失的行程配置取默认值，而不是让整份加载失败。
        assertEquals(TripConfig.defaults(), loaded.trip());
    }

    /**
     * 只写了一部分行程字段的配置（手工编辑或中途升级）同样要能加载，缺失的数值取默认值。
     */
    @Test
    void fillsInDefaultsForPartiallyWrittenTripSections() throws Exception {
        Files.writeString(temporaryDirectory.resolve("config.json"), """
                {
                  "signalHost": "127.0.0.1",
                  "signalPort": 7100,
                  "version": "V2013",
                  "mobileNo": "138000000000",
                  "deviceId": "1380000",
                  "channel": 1,
                  "registration": {
                    "provinceId": 31, "cityId": 100, "makerId": "JT",
                    "deviceModel": "SIMULATOR", "plateColor": 1, "plateNo": "TEST001",
                    "imei": "000000000000000", "softwareVersion": "0.1.0"
                  },
                  "ffmpegPath": "", "cameraName": "", "microphoneName": "",
                  "mainProfile": {
                    "width": 1280, "height": 720, "frameRate": 25,
                    "bitrateKbps": 2000, "gopSeconds": 2
                  },
                  "subProfile": {
                    "width": 640, "height": 360, "frameRate": 15,
                    "bitrateKbps": 512, "gopSeconds": 2
                  },
                  "previewWidth": 640, "previewHeight": 360, "previewFps": 5,
                  "maxPayloadBytes": 1400,
                  "trip": { "amapKey": "abc", "originLat": 31.5 }
                }
                """);

        SimulatorConfig loaded = new ConfigStore(temporaryDirectory).load();

        assertEquals("abc", loaded.trip().amapKey());
        // 数值字段缺失，反序列化后是 0；0 越界，因此回落默认而不是把车速设成 0、间隔设成 0。
        assertEquals(TripConfig.DEFAULT_SPEED_KPH, loaded.trip().speedKph(), 0.0D);
        assertEquals(TripConfig.DEFAULT_REPORT_INTERVAL_SECONDS,
                loaded.trip().reportIntervalSeconds());
        // 只有一半的起点等于没有起点——留着半个点，后面每处用到的地方都得再判一次。
        assertFalse(loaded.trip().hasOrigin());
        assertNull(loaded.trip().originLat());
    }

    /**
     * 检测到外部编码器后会自动存盘，因此这条复制路径漏传任何字段，都会在一个看似无关的时刻
     * 把它悄悄抹掉。
     */
    @Test
    void carriesTripConfigurationThroughTheFfmpegPathCopy() {
        SimulatorConfig configured = new SimulatorConfig(
                "127.0.0.1", 7_100, Jt808Version.V2013, "138000000000", "1380000", 1,
                RegistrationConfig.defaults(), "", "", "",
                VideoProfile.defaultMain(), VideoProfile.defaultSub(), 640, 360, 5, 1_400,
                new TripConfig(true, "key-1", 31.23D, 121.47D, 31.24D, 121.50D, 80.0D, 5, false));

        SimulatorConfig copied = configured.withFfmpegPath("C:/tools/ffmpeg.exe");

        assertEquals("C:/tools/ffmpeg.exe", copied.ffmpegPath());
        assertEquals(configured.trip(), copied.trip());
    }

    private static SimulatorConfig copyIdentity(
            SimulatorConfig source, Jt808Version version, String mobileNo, String deviceId) {
        return new SimulatorConfig(
                source.signalHost(), source.signalPort(), version, mobileNo, deviceId, source.channel(),
                source.registration(), source.ffmpegPath(), source.cameraName(), source.microphoneName(),
                source.mainProfile(), source.subProfile(), source.previewWidth(), source.previewHeight(),
                source.previewFps(), source.maxPayloadBytes(), source.trip());
    }
}
