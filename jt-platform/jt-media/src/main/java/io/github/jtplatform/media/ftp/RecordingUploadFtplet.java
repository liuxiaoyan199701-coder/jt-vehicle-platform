package io.github.jtplatform.media.ftp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;

final class RecordingUploadFtplet extends DefaultFtplet {
    private final TemporaryFtpCredentialService credentials;
    private final RecordingUploadPublisher publisher;

    RecordingUploadFtplet(TemporaryFtpCredentialService credentials, RecordingUploadPublisher publisher) {
        this.credentials = credentials;
        this.publisher = publisher;
    }

    @Override
    public FtpletResult beforeCommand(FtpSession session, FtpRequest request) {
        if (session.isLoggedIn() && !credentials.isActive(session.getUser().getName())) {
            return FtpletResult.DISCONNECT;
        }
        return FtpletResult.DEFAULT;
    }

    @Override
    public FtpletResult onUploadEnd(FtpSession session, FtpRequest request) throws FtpException, IOException {
        String username = session.getUser().getName();
        String taskId = credentials.taskIdForUsername(username);
        String deviceId = credentials.deviceIdForUsername(username);
        if (taskId == null || deviceId == null) return FtpletResult.DEFAULT;
        FtpFile uploaded = session.getFileSystemView().getFile(request.getArgument());
        Path file = physicalPath(uploaded);
        Path home = credentials.homeForUsername(username);
        if (file != null && home != null && file.startsWith(home) && java.nio.file.Files.isRegularFile(file)) {
            publisher.publish(taskId, deviceId, file);
        }
        return FtpletResult.DEFAULT;
    }

    private static Path physicalPath(FtpFile file) {
        Object physical = file == null ? null : file.getPhysicalFile();
        if (physical instanceof File nativeFile) return nativeFile.toPath().toAbsolutePath().normalize();
        if (physical instanceof Path path) return path.toAbsolutePath().normalize();
        return null;
    }
}
