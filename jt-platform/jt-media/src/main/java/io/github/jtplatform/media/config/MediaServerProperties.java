package io.github.jtplatform.media.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.media.server")
public class MediaServerProperties {
    private boolean enabled = true;
    private String bindAddress = "0.0.0.0";
    private int bossThreads = 1;
    private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
    private int backlog = 1024;
    private int maxPayloadBytes = 65_535;
    private int maxFrameBytes = 8 * 1024 * 1024;
    private Duration reassemblyTimeout = Duration.ofSeconds(2);
    private String websocketPath = "/ws";
    private int websocketMaxFrameBytes = 1024 * 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public void setBossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getBacklog() {
        return backlog;
    }

    public void setBacklog(int backlog) {
        this.backlog = backlog;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public void setMaxFrameBytes(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public Duration getReassemblyTimeout() {
        return reassemblyTimeout;
    }

    public void setReassemblyTimeout(Duration reassemblyTimeout) {
        this.reassemblyTimeout = reassemblyTimeout;
    }

    public String getWebsocketPath() {
        return websocketPath;
    }

    public void setWebsocketPath(String websocketPath) {
        this.websocketPath = websocketPath;
    }

    public int getWebsocketMaxFrameBytes() {
        return websocketMaxFrameBytes;
    }

    public void setWebsocketMaxFrameBytes(int websocketMaxFrameBytes) {
        this.websocketMaxFrameBytes = websocketMaxFrameBytes;
    }

    public void validate() {
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalStateException("jt.media.server.bind-address must not be blank");
        }
        if (bossThreads < 1 || workerThreads < 1) {
            throw new IllegalStateException("Media Netty thread counts must be positive");
        }
        if (backlog < 1 || maxPayloadBytes < 1 || maxPayloadBytes > 0xffff || maxFrameBytes < 1) {
            throw new IllegalStateException("Invalid media server size or backlog configuration");
        }
        if (reassemblyTimeout == null || reassemblyTimeout.isZero() || reassemblyTimeout.isNegative()) {
            throw new IllegalStateException("jt.media.server.reassembly-timeout must be positive");
        }
        if (websocketPath == null || !websocketPath.startsWith("/")) {
            throw new IllegalStateException("jt.media.server.websocket-path must start with '/'");
        }
        if (websocketMaxFrameBytes < 1) {
            throw new IllegalStateException("jt.media.server.websocket-max-frame-bytes must be positive");
        }
    }
}
