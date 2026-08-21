package io.github.jtplatform.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FleetIdentityDeriverTest {
    @Test
    void incrementsFixedWidthIdentitiesAndPlateSuffix() {
        SimulatorConfig template = SimulatorConfig.defaults();
        FleetConfig fleet = new FleetConfig(true, 3, true, true, true, 15);

        SimulatorConfig first = FleetIdentityDeriver.derive(template, fleet, 0);
        SimulatorConfig third = FleetIdentityDeriver.derive(template, fleet, 2);

        assertEquals("1380000", first.deviceId());
        assertEquals("1380002", third.deviceId());
        assertEquals("138000000002", third.mobileNo());
        assertEquals("TEST003", third.registration().plateNo());
    }

    @Test
    void rejectsTailCarryOverflowInsteadOfProducingInvalidIdentity() {
        SimulatorConfig source = SimulatorConfig.defaults();
        SimulatorConfig template = new SimulatorConfig(
                source.signalHost(), source.signalPort(), source.version(), "999999999999",
                "999999999999", source.channel(),
                new RegistrationConfig(source.registration().provinceId(), source.registration().cityId(),
                        source.registration().makerId(), source.registration().deviceModel(),
                        source.registration().plateColor(), "TEST999", source.registration().imei(),
                        source.registration().softwareVersion()),
                source.ffmpegPath(), source.cameraName(), source.microphoneName(), source.mainProfile(),
                source.subProfile(), source.previewWidth(), source.previewHeight(), source.previewFps(),
                source.maxPayloadBytes(), source.trip(), source.driver(), source.alarm(), source.simFormat(),
                source.recording(), source.fleet());
        assertThrows(IllegalArgumentException.class,
                () -> FleetIdentityDeriver.derive(template, new FleetConfig(true, 2, true, true, true, 0), 1));
    }
}
