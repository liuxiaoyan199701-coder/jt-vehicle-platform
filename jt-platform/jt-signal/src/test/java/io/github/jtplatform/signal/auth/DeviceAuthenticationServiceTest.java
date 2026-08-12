package io.github.jtplatform.signal.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.yzh.web.model.entity.DeviceDO;

@ExtendWith(OutputCaptureExtension.class)
class DeviceAuthenticationServiceTest {
    private static final DeviceInformationSource UNAVAILABLE = terminalId -> {
        throw new DeviceInformationUnavailableException("business system is offline");
    };

    @Test
    void unavailableRemoteSourceUsesExplicitAllowPolicyAndRecordsDecision(CapturedOutput output) {
        DeviceAuthenticationService service = new DeviceAuthenticationService(
                UNAVAILABLE, DeviceAuthMode.REMOTE_API, RemoteUnavailablePolicy.ALLOW);

        assertTrue(service.authenticate(new DeviceDO().setDeviceId("terminal-1")).allowed());
        assertTrue(output.getOut().contains("explicitPolicy=ALLOW"));
        assertTrue(output.getOut().contains("decision=ALLOW"));
    }

    @Test
    void unavailableRemoteSourceUsesExplicitDenyPolicyAndRecordsDecision(CapturedOutput output) {
        DeviceAuthenticationService service = new DeviceAuthenticationService(
                UNAVAILABLE, DeviceAuthMode.REMOTE_API, RemoteUnavailablePolicy.DENY);

        assertFalse(service.authenticate(new DeviceDO().setDeviceId("terminal-1")).allowed());
        assertTrue(output.getOut().contains("explicitPolicy=DENY"));
        assertTrue(output.getOut().contains("decision=DENY"));
    }

    @Test
    void remoteModeRequiresAnExplicitUnavailablePolicy() {
        assertThrows(IllegalStateException.class,
                () -> new DeviceAuthenticationService(UNAVAILABLE, DeviceAuthMode.REMOTE_API, null));
    }

    @Test
    void businessContractExposesDeviceFactsButNoAccessVerdict() {
        assertFalse(Arrays.stream(DeviceInformation.class.getRecordComponents())
                .anyMatch(component -> component.getType() == boolean.class
                        || component.getName().equalsIgnoreCase("allowed")
                        || component.getName().equalsIgnoreCase("authorized")));
    }
}
