package io.github.jtplatform.common.port;

import java.util.Optional;

public interface DeviceRouter {
    void bind(String deviceId, String signalInstanceId);

    void unbind(String deviceId, String signalInstanceId);

    Optional<String> findSignalInstance(String deviceId);
}
