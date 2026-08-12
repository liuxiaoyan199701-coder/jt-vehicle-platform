package io.github.jtplatform.signal.auth;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

final class LocalListDeviceInformationSource implements DeviceInformationSource {
    private final Set<String> terminalIds;

    LocalListDeviceInformationSource(Set<String> terminalIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (terminalIds != null) {
            for (String terminalId : terminalIds) {
                if (terminalId != null && !terminalId.isBlank()) {
                    normalized.add(terminalId.trim());
                }
            }
        }
        this.terminalIds = Set.copyOf(normalized);
    }

    @Override
    public Optional<DeviceInformation> find(String terminalId) {
        if (terminalId == null) {
            return Optional.empty();
        }
        String normalized = terminalId.trim();
        return terminalIds.contains(normalized)
                ? Optional.of(new DeviceInformation(normalized, normalized, null, null))
                : Optional.empty();
    }
}
