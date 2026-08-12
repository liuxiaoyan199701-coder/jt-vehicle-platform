package io.github.jtplatform.signal.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.signal")
public class SignalProperties {
    private boolean enabled = true;
    private String instanceId = "signal-1";
    private int publicTcpPort = 7100;
    private int publicUdpPort = 7101;
    private Integer tcpPort;
    private Integer udpPort;
    private Duration idleTimeout = Duration.ofSeconds(60);
    private String[] messagePackages = {"org.yzh.protocol"};
    private final Storage storage = new Storage();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public int getPublicTcpPort() {
        return publicTcpPort;
    }

    public void setPublicTcpPort(int publicTcpPort) {
        this.publicTcpPort = publicTcpPort;
    }

    public int getPublicUdpPort() {
        return publicUdpPort;
    }

    public void setPublicUdpPort(int publicUdpPort) {
        this.publicUdpPort = publicUdpPort;
    }

    public Integer getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(Integer tcpPort) {
        this.tcpPort = tcpPort;
    }

    public Integer getUdpPort() {
        return udpPort;
    }

    public void setUdpPort(Integer udpPort) {
        this.udpPort = udpPort;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public String[] getMessagePackages() {
        return messagePackages.clone();
    }

    public void setMessagePackages(String[] messagePackages) {
        this.messagePackages = messagePackages.clone();
    }

    public Storage getStorage() {
        return storage;
    }

    public static class Storage {
        private Path multimediaPath = Path.of("data", "signal", "multimedia");
        private String multimediaAccessBaseUrl = "";
        private Path alarmAttachmentPath = Path.of("data", "signal", "alarm-attachments");

        public Path getMultimediaPath() {
            return multimediaPath;
        }

        public void setMultimediaPath(Path multimediaPath) {
            this.multimediaPath = multimediaPath;
        }

        public String getMultimediaAccessBaseUrl() {
            return multimediaAccessBaseUrl;
        }

        public void setMultimediaAccessBaseUrl(String multimediaAccessBaseUrl) {
            this.multimediaAccessBaseUrl = multimediaAccessBaseUrl;
        }

        public Path getAlarmAttachmentPath() {
            return alarmAttachmentPath;
        }

        public void setAlarmAttachmentPath(Path alarmAttachmentPath) {
            this.alarmAttachmentPath = alarmAttachmentPath;
        }
    }
}
