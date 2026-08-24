package io.github.jtplatform.signal.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.signal")
public class SignalProperties {
    private boolean enabled = true;
    private String instanceId = "signal-1";
    /**
     * Shared key for the {@code /internal/devices/**} administration endpoints. Independent of every
     * other credential in the deployment: the caller is a peer process, not a person. Empty means
     * the endpoints stay closed.
     */
    private String adminKey = "";
    private int publicTcpPort = 7100;
    private int publicUdpPort = 7101;
    private Integer tcpPort;
    private Integer udpPort;
    private Duration idleTimeout = Duration.ofSeconds(60);
    private String[] messagePackages = {"org.yzh.protocol"};
    private final Storage storage = new Storage();
    private final MessageLog messageLog = new MessageLog();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdminKey() {
        return adminKey;
    }

    public void setAdminKey(String adminKey) {
        this.adminKey = adminKey;
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

    public MessageLog getMessageLog() {
        return messageLog;
    }

    /** 报文日志采集。截断上限放在网关侧，超长内容因此进不了投递队列，也进不了落盘 spool。 */
    public static class MessageLog {
        private boolean enabled = true;
        /** 单条原始帧 hex 的字符数上限。8192 字符约等于 4KB 报文，够覆盖除多媒体外的全部帧型。 */
        private int maxHexChars = 8192;
        /** 单条解析 JSON 的字符数上限。 */
        private int maxJsonChars = 8192;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxHexChars() {
            return maxHexChars;
        }

        public void setMaxHexChars(int maxHexChars) {
            this.maxHexChars = maxHexChars;
        }

        public int getMaxJsonChars() {
            return maxJsonChars;
        }

        public void setMaxJsonChars(int maxJsonChars) {
            this.maxJsonChars = maxJsonChars;
        }
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
