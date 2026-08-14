package io.github.jtplatform.signal.auth;

/**
 * Device facts returned by an information source. This type intentionally contains no access decision.
 *
 * <p>{@code tenantCode} and {@code tenantActive} are facts about the owning tenant, not a verdict:
 * a source states whether the tenant is currently usable, and this module alone decides what that
 * means for access. Sources with no tenancy concept (allow-all, local-list) leave both null.
 */
public record DeviceInformation(
        String terminalId,
        String deviceId,
        String mobileNo,
        String plateNo,
        String tenantCode,
        Boolean tenantActive) {

    public DeviceInformation {
        terminalId = normalize(terminalId);
        deviceId = requireText(deviceId, "deviceId");
        mobileNo = normalize(mobileNo);
        plateNo = normalize(plateNo);
        tenantCode = normalize(tenantCode);
    }

    /** Facts without any tenancy context. */
    public DeviceInformation(String terminalId, String deviceId, String mobileNo, String plateNo) {
        this(terminalId, deviceId, mobileNo, plateNo, null, null);
    }

    public boolean describes(String requestedTerminalId) {
        String requested = requireText(requestedTerminalId, "requestedTerminalId");
        return terminalId == null || terminalId.equals(requested);
    }

    /**
     * A source that reports tenancy and says the tenant is not usable. Absent tenancy information
     * is never treated as inactive — that would deny every device the moment a source stops
     * reporting the field.
     */
    public boolean tenantInactive() {
        return Boolean.FALSE.equals(tenantActive);
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
