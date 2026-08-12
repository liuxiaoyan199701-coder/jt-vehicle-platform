package io.github.jtplatform.signal.auth;

/** Device facts returned by an information source. This type intentionally contains no access decision. */
public record DeviceInformation(
        String terminalId,
        String deviceId,
        String mobileNo,
        String plateNo) {

    public DeviceInformation {
        terminalId = normalize(terminalId);
        deviceId = requireText(deviceId, "deviceId");
        mobileNo = normalize(mobileNo);
        plateNo = normalize(plateNo);
    }

    public boolean describes(String requestedTerminalId) {
        String requested = requireText(requestedTerminalId, "requestedTerminalId");
        return terminalId == null || terminalId.equals(requested);
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
