package io.github.jtplatform.api.stream;

import java.time.Instant;

public record ApiError(String code, String message, Instant timestamp) {}
