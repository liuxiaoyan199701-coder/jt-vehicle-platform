package io.github.jtplatform.signal.auth;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yzh.web.model.entity.DeviceDO;

public final class DeviceAuthenticationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceAuthenticationService.class);

    private final DeviceInformationSource informationSource;
    private final DeviceAuthMode mode;
    private final RemoteUnavailablePolicy unavailablePolicy;

    public DeviceAuthenticationService(
            DeviceInformationSource informationSource,
            DeviceAuthMode mode,
            RemoteUnavailablePolicy unavailablePolicy) {
        this.informationSource = Objects.requireNonNull(informationSource, "informationSource");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (mode == DeviceAuthMode.REMOTE_API && unavailablePolicy == null) {
            throw new IllegalStateException(
                    "jt.auth.device.remote.unavailable-policy must be explicitly set to ALLOW or DENY in remote-api mode");
        }
        this.unavailablePolicy = unavailablePolicy;
    }

    public DeviceAuthenticationDecision authenticate(DeviceDO presentedDevice) {
        Objects.requireNonNull(presentedDevice, "presentedDevice");
        String terminalId = terminalIdOf(presentedDevice);
        if (terminalId == null) {
            return DeviceAuthenticationDecision.deny();
        }

        Optional<DeviceInformation> information;
        try {
            information = informationSource.find(terminalId);
        } catch (DeviceInformationUnavailableException exception) {
            return decideUnavailable(terminalId, presentedDevice, exception);
        }
        if (information.isEmpty()) {
            return DeviceAuthenticationDecision.deny();
        }

        DeviceInformation found = information.get();
        if (!found.describes(terminalId)) {
            LOGGER.warn("Device information mismatch; terminalId={}, returnedTerminalId={}, decision=DENY",
                    terminalId, found.terminalId());
            return DeviceAuthenticationDecision.deny();
        }
        return DeviceAuthenticationDecision.allow(merge(presentedDevice, found));
    }

    private DeviceAuthenticationDecision decideUnavailable(
            String terminalId,
            DeviceDO presentedDevice,
            DeviceInformationUnavailableException exception) {
        if (mode != DeviceAuthMode.REMOTE_API) {
            throw exception;
        }
        boolean allow = unavailablePolicy == RemoteUnavailablePolicy.ALLOW;
        LOGGER.warn(
                "Remote device information unavailable; terminalId={}, explicitPolicy={}, decision={}, reason={}",
                terminalId,
                unavailablePolicy,
                allow ? "ALLOW" : "DENY",
                exception.getMessage());
        return allow
                ? DeviceAuthenticationDecision.allow(presentedDevice)
                : DeviceAuthenticationDecision.deny();
    }

    private static DeviceDO merge(DeviceDO presented, DeviceInformation found) {
        DeviceDO resolved = new DeviceDO(presented);
        if (isBlank(resolved.getDeviceId())) {
            resolved.setDeviceId(found.deviceId());
        }
        if (!isBlank(found.mobileNo())) {
            resolved.setMobileNo(found.mobileNo());
        }
        if (!isBlank(found.plateNo())) {
            resolved.setPlateNo(found.plateNo());
        }
        return resolved;
    }

    private static String terminalIdOf(DeviceDO device) {
        if (!isBlank(device.getDeviceId())) {
            return device.getDeviceId().trim();
        }
        if (!isBlank(device.getMobileNo())) {
            return device.getMobileNo().trim();
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
