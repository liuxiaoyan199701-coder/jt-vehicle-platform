package io.github.jtplatform.simulator.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jtplatform.simulator.config.ConfigStore;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.TripConfig;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapSelectionBridgeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bridgePreservesSixDecimalGcj02Coordinates() {
        AtomicReference<MapSelection> selected = new AtomicReference<>();
        MapSelectionBridge bridge = new MapSelectionBridge(null, null, selected::set);
        bridge.setOrigin(31.230416, 121.473701);
        bridge.setDestination(30.274085, 120.155070);
        bridge.confirm();

        assertEquals(31.230416, selected.get().origin().latitude(), 0.000000001);
        assertEquals(121.473701, selected.get().origin().longitude(), 0.000000001);
        assertEquals("{\"origin\":{\"lat\":31.230416,\"lng\":121.473701},\"destination\":{\"lat\":30.274085,\"lng\":120.155070}}",
                bridge.initialStateJson());
    }

    @Test
    void cannotConfirmBeforeBothPointsAreSelected() {
        MapSelectionBridge bridge = new MapSelectionBridge(null, null, ignored -> { });
        assertThrows(IllegalStateException.class, bridge::confirm);
    }

    @Test
    void selectedCoordinatesPersistThroughSimulatorConfig() throws Exception {
        SimulatorConfig defaults = SimulatorConfig.defaults();
        TripConfig trip = new TripConfig(false, "", 31.230416, 121.473701,
                30.274085, 120.155070, 60, 10, true);
        SimulatorConfig selected = new SimulatorConfig(
                defaults.signalHost(), defaults.signalPort(), defaults.version(), defaults.mobileNo(),
                defaults.deviceId(), defaults.channel(), defaults.registration(), defaults.ffmpegPath(),
                defaults.cameraName(), defaults.microphoneName(), defaults.mainProfile(), defaults.subProfile(),
                defaults.previewWidth(), defaults.previewHeight(), defaults.previewFps(), defaults.maxPayloadBytes(),
                trip, defaults.driver(), defaults.alarm(), defaults.simFormat(), defaults.recording(),
                defaults.fleet(), defaults.terminalManagement(), defaults.waybill());
        ConfigStore store = new ConfigStore(temporaryDirectory);
        store.save(selected);
        SimulatorConfig loaded = store.load();

        assertEquals(31.230416, loaded.trip().originLat(), 0.000000001);
        assertEquals(121.473701, loaded.trip().originLng(), 0.000000001);
        assertEquals(30.274085, loaded.trip().destinationLat(), 0.000000001);
        assertEquals(120.155070, loaded.trip().destinationLng(), 0.000000001);
    }
}
