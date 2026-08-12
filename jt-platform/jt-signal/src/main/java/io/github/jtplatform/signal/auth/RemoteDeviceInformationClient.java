package io.github.jtplatform.signal.auth;

import java.util.Optional;

/** Queries device facts only. Implementations must not return an access-control verdict. */
@FunctionalInterface
public interface RemoteDeviceInformationClient {
    Optional<DeviceInformation> find(String terminalId);
}
