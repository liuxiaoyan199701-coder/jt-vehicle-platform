package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.DriverConfig;
import io.github.jtplatform.simulator.config.Jt808Version;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0702;
import org.yzh.protocol.basics.JTMessage;

class SignalClientFeatureTest {
    @Test
    void locationReportCarriesAtomicAlarmSetAndOverspeedLinkage() {
        SimulatorConfig config = SimulatorConfig.defaults();
        SignalClient client = new SignalClient(config, noCommands(), null);
        LocationFix fix = new LocationFix(31.2, 121.4, 10, 40, 90, 100, 
                LocalDateTime.of(2026, 8, 20, 10, 0));

        client.setAlarm(AlarmDefinition.FATIGUE_BIT, true);
        client.setAlarm(AlarmDefinition.GNSS_MODULE_FAULT_BIT, true);
        client.setAlarm(AlarmDefinition.OVERSPEED_BIT, true);
        client.setOverspeedKph(88.0);
        T0200 report = client.locationReportForTest(fix);
        assertTrue((report.getWarnBit() & (1 << AlarmDefinition.FATIGUE_BIT)) != 0);
        assertTrue((report.getWarnBit() & (1 << AlarmDefinition.GNSS_MODULE_FAULT_BIT)) != 0);
        assertEquals(880, report.getSpeed());

        client.setAlarm(AlarmDefinition.FATIGUE_BIT, false);
        report = client.locationReportForTest(fix);
        assertFalse((report.getWarnBit() & (1 << AlarmDefinition.FATIGUE_BIT)) != 0);
        assertTrue((report.getWarnBit() & (1 << AlarmDefinition.GNSS_MODULE_FAULT_BIT)) != 0);
        client.close();
    }

    @Test
    void driverMessagesUseProtocolFieldsFailureResultAndEastEightTime() throws Exception {
        SimulatorConfig defaults = SimulatorConfig.defaults();
        SimulatorConfig v2019 = new SimulatorConfig(
                defaults.signalHost(), defaults.signalPort(), Jt808Version.V2019,
                "00000000138000000000", defaults.deviceId(), defaults.channel(), defaults.registration(),
                defaults.ffmpegPath(), defaults.cameraName(), defaults.microphoneName(),
                defaults.mainProfile(), defaults.subProfile(), defaults.previewWidth(), defaults.previewHeight(),
                defaults.previewFps(), defaults.maxPayloadBytes(), defaults.trip(), defaults.driver(),
                defaults.alarm(), defaults.simFormat());
        SignalClient client = new SignalClient(v2019, noCommands(), null);
        DriverConfig driver = new DriverConfig("张三", "110101199001010011", "資格-1", "发证机构", "2608");
        Instant instant = Instant.parse("2026-08-20T02:00:00Z");

        T0702 insert = client.driverMessageForTest(driver, SignalClient.DriverAction.INSERT_CARD, instant);
        JTMessage decoded = new Jt808MessageCodec().decode(new Jt808MessageCodec().encode(insert));
        assertTrue(decoded instanceof T0702);
        insert = (T0702) decoded;
        assertEquals(1, insert.getStatus());
        assertEquals(0, insert.getCardStatus());
        assertEquals("260820100000", insert.getDateTime());
        assertEquals(driver.name(), insert.getName());
        assertEquals(driver.idCard(), insert.getIdCard());
        assertEquals(driver.licenseNo(), insert.getLicenseNo());
        assertEquals(driver.institution(), insert.getInstitution());
        assertEquals(driver.licenseValidPeriod(), insert.getLicenseValidPeriod());

        T0702 failure = client.driverMessageForTest(driver, SignalClient.DriverAction.READ_FAILURE, instant);
        assertTrue(failure.getCardStatus() != 0);
        client.close();
    }

    private static SignalCommandHandler noCommands() {
        return new SignalCommandHandler() {
            @Override public java.util.concurrent.CompletionStage<Integer> open(org.yzh.protocol.t1078.T9101 command) {
                return java.util.concurrent.CompletableFuture.completedFuture(0);
            }
            @Override public java.util.concurrent.CompletionStage<Integer> control(org.yzh.protocol.t1078.T9102 command) {
                return java.util.concurrent.CompletableFuture.completedFuture(0);
            }
            @Override public void onSignalDisconnected() { }
        };
    }
}
