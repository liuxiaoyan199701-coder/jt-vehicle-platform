package io.github.jtplatform.media.ftp;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.media.ftp")
public class RecordingFtpProperties {
    private boolean enabled;
    private String bindAddress = "0.0.0.0";
    private String advertisedAddress = "";
    private int port = 2121;
    private String passivePorts = "30000-30009";
    private Path root = Path.of("recordings", "uploads");
    private Duration credentialTtl = Duration.ofMinutes(30);
    private String accessBaseUrl = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }
    public String getAdvertisedAddress() { return advertisedAddress; }
    public void setAdvertisedAddress(String advertisedAddress) { this.advertisedAddress = advertisedAddress; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getPassivePorts() { return passivePorts; }
    public void setPassivePorts(String passivePorts) { this.passivePorts = passivePorts; }
    public Path getRoot() { return root; }
    public void setRoot(Path root) { this.root = root; }
    public Duration getCredentialTtl() { return credentialTtl; }
    public void setCredentialTtl(Duration credentialTtl) { this.credentialTtl = credentialTtl; }
    public String getAccessBaseUrl() { return accessBaseUrl; }
    public void setAccessBaseUrl(String accessBaseUrl) { this.accessBaseUrl = accessBaseUrl; }

    public void validate() {
        if (bindAddress == null || bindAddress.isBlank()) throw new IllegalStateException("jt.media.ftp.bind-address must not be blank");
        if (port < 1 || port > 65535) throw new IllegalStateException("jt.media.ftp.port must be in range 1..65535");
        if (passivePorts == null || passivePorts.isBlank()) throw new IllegalStateException("jt.media.ftp.passive-ports must not be blank");
        if (root == null) throw new IllegalStateException("jt.media.ftp.root must not be null");
        if (credentialTtl == null || credentialTtl.isZero() || credentialTtl.isNegative()) {
            throw new IllegalStateException("jt.media.ftp.credential-ttl must be positive");
        }
    }
}
