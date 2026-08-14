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
    private final UnregisteredDevicePolicy unregisteredPolicy;

    public DeviceAuthenticationService(
            DeviceInformationSource informationSource,
            DeviceAuthMode mode,
            RemoteUnavailablePolicy unavailablePolicy) {
        this(informationSource, mode, unavailablePolicy, UnregisteredDevicePolicy.REJECT);
    }

    public DeviceAuthenticationService(
            DeviceInformationSource informationSource,
            DeviceAuthMode mode,
            RemoteUnavailablePolicy unavailablePolicy,
            UnregisteredDevicePolicy unregisteredPolicy) {
        this.informationSource = Objects.requireNonNull(informationSource, "informationSource");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (mode == DeviceAuthMode.REMOTE_API && unavailablePolicy == null) {
            throw new IllegalStateException(
                    "jt.auth.device.remote.unavailable-policy must be explicitly set to ALLOW or DENY in remote-api mode");
        }
        this.unavailablePolicy = unavailablePolicy;
        this.unregisteredPolicy = Objects.requireNonNull(unregisteredPolicy, "unregisteredPolicy");
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
            return decideUnregistered(terminalId, presentedDevice);
        }

        DeviceInformation found = information.get();
        if (!found.describes(terminalId)) {
            LOGGER.warn("Device information mismatch; terminalId={}, returnedTerminalId={}, decision=DENY",
                    terminalId, found.terminalId());
            return DeviceAuthenticationDecision.deny();
        }
        if (found.tenantInactive()) {
            LOGGER.info("Owning tenant is not active; terminalId={}, tenant={}, decision=DENY",
                    terminalId, found.tenantCode());
            return DeviceAuthenticationDecision.deny();
        }
        return DeviceAuthenticationDecision.allow(merge(presentedDevice, found));
    }

    /**
     * A device with no archive. Only remote-api mode consults the configured policy: in
     * allow-all mode the source never reports empty, and in local-list mode "absent from the
     * list" already is the rejection.
     */
    private DeviceAuthenticationDecision decideUnregistered(
            String terminalId, DeviceDO presentedDevice) {
        if (mode != DeviceAuthMode.REMOTE_API
                || unregisteredPolicy != UnregisteredDevicePolicy.ALLOW) {
            return DeviceAuthenticationDecision.deny();
        }
        LOGGER.info("Device has no archive; terminalId={}, explicitPolicy=ALLOW, decision=ALLOW",
                terminalId);
        return DeviceAuthenticationDecision.allow(presentedDevice);
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
