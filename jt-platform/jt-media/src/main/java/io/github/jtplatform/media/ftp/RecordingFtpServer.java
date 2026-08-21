package io.github.jtplatform.media.ftp;

import java.util.HashMap;
import java.util.Map;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.listener.ListenerFactory;
import org.springframework.context.SmartLifecycle;

/** Embedded passive-only Apache MINA FtpServer. */
public final class RecordingFtpServer implements SmartLifecycle {
    private final RecordingFtpProperties properties;
    private final TemporaryFtpCredentialService credentials;
    private final RecordingUploadPublisher publisher;
    private volatile FtpServer server;

    public RecordingFtpServer(RecordingFtpProperties properties,
                       TemporaryFtpCredentialService credentials,
                       RecordingUploadPublisher publisher) {
        this.properties = properties;
        this.credentials = credentials;
        this.publisher = publisher;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) return;
        properties.validate();
        DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setActiveEnabled(false);
        data.setPassivePorts(properties.getPassivePorts());
        String advertised = properties.getAdvertisedAddress();
        if (advertised != null && !advertised.isBlank()) data.setPassiveExternalAddress(advertised.trim());

        ListenerFactory listener = new ListenerFactory();
        listener.setServerAddress(properties.getBindAddress());
        listener.setPort(properties.getPort());
        listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());

        NativeFileSystemFactory files = new NativeFileSystemFactory();
        files.setCreateHome(false);
        FtpServerFactory factory = new FtpServerFactory();
        factory.setUserManager(credentials);
        factory.setFileSystem(files);
        Map<String, org.apache.ftpserver.ftplet.Ftplet> ftplets = new HashMap<>();
        ftplets.put("recordingUpload", new RecordingUploadFtplet(credentials, publisher));
        factory.setFtplets(ftplets);
        factory.addListener("default", listener.createListener());
        FtpServer candidate = factory.createServer();
        try {
            candidate.start();
            server = candidate;
        } catch (FtpException failure) {
            candidate.stop();
            throw new IllegalStateException("Unable to start passive recording FTP server", failure);
        }
    }

    @Override public synchronized void stop() { if (server != null) { server.stop(); server = null; } }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return server != null && !server.isStopped(); }
    @Override public int getPhase() { return Integer.MAX_VALUE - 200; }
}
