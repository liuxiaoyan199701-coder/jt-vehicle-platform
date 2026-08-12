package io.github.jtplatform.signal.auth;

import java.util.Optional;

@FunctionalInterface
public interface DeviceInformationSource {
    Optional<DeviceInformation> find(String terminalId);
}
