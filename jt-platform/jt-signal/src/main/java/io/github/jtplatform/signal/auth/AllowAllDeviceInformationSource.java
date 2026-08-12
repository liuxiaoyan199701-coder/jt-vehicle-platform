package io.github.jtplatform.signal.auth;

import java.util.Optional;

final class AllowAllDeviceInformationSource implements DeviceInformationSource {
    @Override
    public Optional<DeviceInformation> find(String terminalId) {
        String normalized = normalize(terminalId);
        return normalized == null
                ? Optional.empty()
                : Optional.of(new DeviceInformation(normalized, normalized, null, null));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
