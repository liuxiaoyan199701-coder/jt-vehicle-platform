package io.github.jtplatform.boot.cluster;

final class ClusterStateException extends RuntimeException {
    ClusterStateException(String message) {
        super(message);
    }

    ClusterStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
