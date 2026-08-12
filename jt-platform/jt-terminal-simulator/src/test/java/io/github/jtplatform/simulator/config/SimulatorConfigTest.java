package io.github.jtplatform.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static SimulatorConfig copyIdentity(
            SimulatorConfig source, Jt808Version version, String mobileNo, String deviceId) {
        return new SimulatorConfig(
                source.signalHost(), source.signalPort(), version, mobileNo, deviceId, source.channel(),
                source.registration(), source.ffmpegPath(), source.cameraName(), source.microphoneName(),
                source.mainProfile(), source.subProfile(), source.previewWidth(), source.previewHeight(),
                source.previewFps(), source.maxPayloadBytes());
    }
}
