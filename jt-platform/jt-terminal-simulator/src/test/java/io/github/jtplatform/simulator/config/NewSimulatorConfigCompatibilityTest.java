package io.github.jtplatform.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yzh.protocol.t1078.codec.Jt1078SimFormat;

class NewSimulatorConfigCompatibilityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void oldConfigWithoutAlarmDriverOrSimFormatKeepsItsExistingValues() throws Exception {
        Files.writeString(temporaryDirectory.resolve("config.json"), """
                {"signalHost":"10.0.0.9","signalPort":7200,"version":"V2013",
                 "mobileNo":"123456789012","deviceId":"654321","channel":3,
                 "registration":{"provinceId":44,"cityId":300,"makerId":"ACME",
                 "deviceModel":"TERM-9","plateColor":2,"plateNo":"TEST001",
                 "imei":"123456789012345","softwareVersion":"9.9.9"},
                 "ffmpegPath":"","cameraName":"","microphoneName":"",
                 "mainProfile":{"width":1280,"height":720,"frameRate":25,"bitrateKbps":2000,"gopSeconds":2},
                 "subProfile":{"width":640,"height":360,"frameRate":15,"bitrateKbps":512,"gopSeconds":2},
                 "previewWidth":640,"previewHeight":360,"previewFps":5,"maxPayloadBytes":1400}
                """);

        SimulatorConfig loaded = new ConfigStore(temporaryDirectory).load();

        assertEquals("10.0.0.9", loaded.signalHost());
        assertEquals(AlarmConfig.defaults(), loaded.alarm());
        assertEquals(DriverConfig.defaults(), loaded.driver());
        assertEquals(Jt1078SimFormat.STANDARD, loaded.simFormat());
        assertEquals(RecordingConfig.defaults(), loaded.recording());
        assertEquals(FleetConfig.defaults(), loaded.fleet());
    }
}
