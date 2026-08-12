package io.github.jtplatform.signal.auth;

import java.util.Objects;
import org.yzh.web.model.entity.DeviceDO;

public record DeviceAuthenticationDecision(boolean allowed, DeviceDO device) {
    public DeviceAuthenticationDecision {
        if (allowed) {
            Objects.requireNonNull(device, "device");
        } else if (device != null) {
            throw new IllegalArgumentException("denied decision must not expose device information");
        }
    }

    public static DeviceAuthenticationDecision allow(DeviceDO device) {
        return new DeviceAuthenticationDecision(true, new DeviceDO(device));
    }

    public static DeviceAuthenticationDecision deny() {
        return new DeviceAuthenticationDecision(false, null);
    }
}
