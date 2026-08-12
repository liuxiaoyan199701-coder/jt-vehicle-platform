package io.github.jtplatform.common.port;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryDeviceRouter implements DeviceRouter {
    private final ConcurrentHashMap<String, String> routes = new ConcurrentHashMap<>();

    @Override
    public void bind(String deviceId, String signalInstanceId) {
        routes.put(requireText(deviceId, "deviceId"), requireText(signalInstanceId, "signalInstanceId"));
    }

    @Override
    public void unbind(String deviceId, String signalInstanceId) {
        routes.remove(requireText(deviceId, "deviceId"), requireText(signalInstanceId, "signalInstanceId"));
    }

    @Override
    public Optional<String> findSignalInstance(String deviceId) {
        return Optional.ofNullable(routes.get(requireText(deviceId, "deviceId")));
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }
}
