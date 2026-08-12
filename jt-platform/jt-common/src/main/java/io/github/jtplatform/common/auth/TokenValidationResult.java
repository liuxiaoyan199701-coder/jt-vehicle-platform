package io.github.jtplatform.common.auth;

public enum TokenValidationResult {
    VALID,
    MISSING,
    EXPIRED,
    WRONG_STREAM,
    WRONG_INSTANCE,
    REPLAYED
}
