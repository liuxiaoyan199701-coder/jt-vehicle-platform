package io.github.jtplatform.common.port;

import java.time.Instant;

/**
 * Issues and revokes task-scoped FTP credentials for JT/T 1078 recording uploads.
 * Implementations must never reuse a credential between tasks.
 */
public interface RecordingUploadCredentialPort {

    RecordingUploadCredentials issue(String taskId, String deviceId);

    void bindCommand(String taskId, String deviceId, int commandSerialNo);

    void completeCommand(String deviceId, int commandSerialNo);

    void revokeTask(String taskId);

    record RecordingUploadCredentials(
            String taskId,
            String serverAddress,
            int port,
            String username,
            String password,
            String path,
            Instant expiresAt) {

        /** Credentials must never leak through diagnostics, exceptions, or structured logs. */
        @Override
        public String toString() {
            return "RecordingUploadCredentials[taskId=" + taskId
                    + ", serverAddress=" + serverAddress
                    + ", port=" + port
                    + ", username=<redacted>, password=<redacted>"
                    + ", path=" + path + ", expiresAt=" + expiresAt + ']';
        }
    }
}
