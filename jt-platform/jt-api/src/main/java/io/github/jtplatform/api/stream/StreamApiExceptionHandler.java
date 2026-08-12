package io.github.jtplatform.api.stream;

import io.github.jtplatform.api.auth.JwtVerificationException;
import io.github.jtplatform.common.port.StreamCommandException;
import io.github.jtplatform.common.service.NoMediaCapacityException;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class StreamApiExceptionHandler {
    @ExceptionHandler(JwtVerificationException.class)
    ResponseEntity<ApiError> unauthorized(JwtVerificationException exception) {
        return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", exception.getMessage());
    }

    @ExceptionHandler(NoMediaCapacityException.class)
    ResponseEntity<ApiError> unavailable(NoMediaCapacityException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "NO_MEDIA_CAPACITY", exception.getMessage());
    }

    @ExceptionHandler(StreamCommandException.class)
    ResponseEntity<ApiError> commandFailed(StreamCommandException exception) {
        return error(HttpStatus.BAD_GATEWAY, "SIGNAL_COMMAND_FAILED", exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ApiError> recordingStorageFailed(IOException exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "RECORDING_STORAGE_FAILED", exception.getMessage());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Instant.now()));
    }
}
