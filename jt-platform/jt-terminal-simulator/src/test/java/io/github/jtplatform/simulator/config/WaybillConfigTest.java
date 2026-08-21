package io.github.jtplatform.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaybillConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void oldConfigWithoutWaybillUsesDisabledDefault() throws Exception {
        SimulatorConfig defaults = SimulatorConfig.defaults();
        Files.writeString(temporaryDirectory.resolve("config.json"), """
                {"signalHost":"127.0.0.1","signalPort":7100,"version":"V2013",
                 "mobileNo":"138000000000","deviceId":"1380000","channel":1,
                 "registration":{"provinceId":31,"cityId":100,"makerId":"JT",
                 "deviceModel":"SIMULATOR","plateColor":1,"plateNo":"TEST001",
                 "imei":"000000000000000","softwareVersion":"0.1.0"},
                 "ffmpegPath":"","cameraName":"","microphoneName":"",
                 "mainProfile":%s,"subProfile":%s,"previewWidth":640,"previewHeight":360,
                 "previewFps":5,"maxPayloadBytes":1400}
                """.formatted(json(defaults.mainProfile()), json(defaults.subProfile())));

        SimulatorConfig loaded = new ConfigStore(temporaryDirectory).load();
        assertFalse(loaded.waybill().autoSendOnTripStart());
        assertEquals(WaybillConfig.JSON_TEMPLATE, loaded.waybill().content());
    }

    @Test
    void waybillConfigRoundTripsAutoSendAndContent() throws Exception {
        SimulatorConfig expected = SimulatorConfig.defaults().withWaybill(
                new WaybillConfig(true, WaybillConfig.TEXT_TEMPLATE));
        ConfigStore store = new ConfigStore(temporaryDirectory);
        store.save(expected);
        assertEquals(expected.waybill(), store.load().waybill());
    }

    private static String json(Object value) throws Exception {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
    }
}
