package io.github.jtconsole.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.console")
public class ConsoleProperties {

    private Gateway gateway = new Gateway();
    private Media media = new Media();
    private Security security = new Security();
    private Broadcast broadcast = new Broadcast();
    private Operations operations = new Operations();
    private Audit audit = new Audit();
    private Registration registration = new Registration();
    private Tenancy tenancy = new Tenancy();
    private Duration offlineTimeout = Duration.ofMinutes(5);
    private Duration eventRetention = Duration.ofHours(24);

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit audit) {
        this.audit = audit;
    }

    public Registration getRegistration() {
        return registration;
    }

    public void setRegistration(Registration registration) {
        this.registration = registration;
    }

    public Tenancy getTenancy() {
        return tenancy;
    }

    public void setTenancy(Tenancy tenancy) {
        this.tenancy = tenancy;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public void setGateway(Gateway gateway) {
        this.gateway = gateway;
    }

    public Media getMedia() {
        return media;
    }

    public void setMedia(Media media) {
        this.media = media;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Broadcast getBroadcast() {
        return broadcast;
    }

    public void setBroadcast(Broadcast broadcast) {
        this.broadcast = broadcast;
    }

    public Operations getOperations() {
        return operations;
    }

    public void setOperations(Operations operations) {
        this.operations = operations;
    }

    public Duration getOfflineTimeout() {
        return offlineTimeout;
    }

    public void setOfflineTimeout(Duration offlineTimeout) {
        this.offlineTimeout = offlineTimeout;
    }

    public Duration getEventRetention() {
        return eventRetention;
    }

    public void setEventRetention(Duration eventRetention) {
        this.eventRetention = eventRetention;
    }

    public static class Media {
        /**
         * 媒体 WebSocket 的对外地址前缀，例如 {@code wss://console.example.com/media-ws}。
         *
         * <p>网关返回的 wsUrl 直连媒体节点的 7815 明文端口（{@code ws://}）。页面一旦以 HTTPS
         * 提供，浏览器会按混合内容策略拦掉所有 {@code ws://} 连接，视频就打不开。配置该项后，
         * 开流响应里的 wsUrl 会被改写成经 nginx 转发的 wss 地址，查询参数（含一次性 token）原样保留。
         *
         * <p>留空表示不改写，直接把网关的地址交给播放器（纯 HTTP 部署时如此）。
         */
        private String publicWebsocketBaseUrl = "";

        public String getPublicWebsocketBaseUrl() {
            return publicWebsocketBaseUrl;
        }

        public void setPublicWebsocketBaseUrl(String publicWebsocketBaseUrl) {
            this.publicWebsocketBaseUrl = publicWebsocketBaseUrl;
        }
    }

    public static class Gateway {
        private String baseUrl = "http://127.0.0.1:8100";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(8);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    public static class Security {
        private boolean deploymentMode;
        private String adminUsername = "admin";
        private String adminPasswordHash = "";
        private String ingestKey = "";
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(7);
        private List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://localhost:9527",
                "http://127.0.0.1:9527"));
        private RateLimit rateLimit = new RateLimit();

        public boolean isDeploymentMode() {
            return deploymentMode;
        }

        public void setDeploymentMode(boolean deploymentMode) {
            this.deploymentMode = deploymentMode;
        }

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminPasswordHash() {
            return adminPasswordHash;
        }

        public void setAdminPasswordHash(String adminPasswordHash) {
            this.adminPasswordHash = adminPasswordHash;
        }

        public String getIngestKey() {
            return ingestKey;
        }

        public void setIngestKey(String ingestKey) {
            this.ingestKey = ingestKey;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = new ArrayList<>(allowedOrigins);
        }

        public RateLimit getRateLimit() {
            return rateLimit;
        }

        public void setRateLimit(RateLimit rateLimit) {
            this.rateLimit = rateLimit;
        }
    }

    public static class RateLimit {
        private int maxFailures = 5;
        private Duration window = Duration.ofMinutes(1);
        private Duration blockDuration = Duration.ofMinutes(5);
        private int maxEntries = 10_000;

        public int getMaxFailures() {
            return maxFailures;
        }

        public void setMaxFailures(int maxFailures) {
            this.maxFailures = maxFailures;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public Duration getBlockDuration() {
            return blockDuration;
        }

        public void setBlockDuration(Duration blockDuration) {
            this.blockDuration = blockDuration;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    public static class Broadcast {
        private int dispatchQueueCapacity = 1024;
        private int sessionQueueCapacity = 64;
        private int workerThreads = 2;
        private Duration sendTimeout = Duration.ofSeconds(5);

        public int getDispatchQueueCapacity() {
            return dispatchQueueCapacity;
        }

        public void setDispatchQueueCapacity(int dispatchQueueCapacity) {
            this.dispatchQueueCapacity = dispatchQueueCapacity;
        }

        public int getSessionQueueCapacity() {
            return sessionQueueCapacity;
        }

        public void setSessionQueueCapacity(int sessionQueueCapacity) {
            this.sessionQueueCapacity = sessionQueueCapacity;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }
    }

    public static class Operations {
        /** 车辆自然日、首页七日窗口和按日汇总统一使用的业务时区。 */
        private String zoneId = "Asia/Shanghai";

        public String getZoneId() {
            return zoneId;
        }

        public void setZoneId(String zoneId) {
            this.zoneId = zoneId;
        }
    }

    public static class Audit {
        /** 有界队列容量。满了丢弃并计数，绝不阻塞业务请求。 */
        private int queueCapacity = 4096;
        /** 保留期。超期记录由每日任务分批清理。 */
        private Duration retention = Duration.ofDays(180);
        /** 单批删除条数，避免一条长事务阻塞业务写入。 */
        private int cleanupBatchSize = 500;
        /** 单次清理的最大批数，给清理任务一个时间上界。 */
        private int cleanupMaxBatches = 100;
        /** 清理任务的 cron 表达式，默认凌晨低峰执行。 */
        private String cleanupCron = "0 17 3 * * *";

        public String getCleanupCron() {
            return cleanupCron;
        }

        public void setCleanupCron(String cleanupCron) {
            this.cleanupCron = cleanupCron;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getCleanupMaxBatches() {
            return cleanupMaxBatches;
        }

        public void setCleanupMaxBatches(int cleanupMaxBatches) {
            this.cleanupMaxBatches = cleanupMaxBatches;
        }
    }

    public static class Registration {
        /**
         * 自助注册入口开关。默认关闭：注册接口是公网可达的写入口，
         * 应由部署方按商务需要显式开启，而不是装上就开着。
         */
        private boolean enabled = false;
        /** 待审批申请的自动过期时限。 */
        private Duration pendingExpiry = Duration.ofDays(30);
        /** 图形验证码有效期。 */
        private Duration captchaTtl = Duration.ofMinutes(5);
        /** 同时保留的验证码上限，防止公网入口把内存打满。 */
        private int captchaMaxEntries = 5_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getPendingExpiry() {
            return pendingExpiry;
        }

        public void setPendingExpiry(Duration pendingExpiry) {
            this.pendingExpiry = pendingExpiry;
        }

        public Duration getCaptchaTtl() {
            return captchaTtl;
        }

        public void setCaptchaTtl(Duration captchaTtl) {
            this.captchaTtl = captchaTtl;
        }

        public int getCaptchaMaxEntries() {
            return captchaMaxEntries;
        }

        public void setCaptchaMaxEntries(int captchaMaxEntries) {
            this.captchaMaxEntries = captchaMaxEntries;
        }
    }

    public static class Tenancy {
        /**
         * 网关查询设备档案使用的共享密钥。与投递密钥、管理员凭据、token 相互独立；
         * 留空表示不开放该接口（网关仍可用 allow-all / local-list 模式）。
         */
        private String deviceRegistryKey = "";
        /**
         * 到期扫描周期（毫秒）。用毫秒而不是 Duration，是因为 {@code @Scheduled} 的
         * fixedDelayString 只认 ISO-8601 或纯数字，"1h" 这种 Boot 风格写法会解析失败。
         * 登录与档案接口另有实时判定，本任务只负责状态落库与联动。
         */
        private long expiryScanMillis = 3_600_000L;
        /** 平台显示名称的全局默认值，可被租户配置覆盖。 */
        private String platformName = "车联网监控平台";
        /** 高德地图 Key 的全局默认值，可被租户配置覆盖。 */
        private String amapKey = "";
        /** 高德地图安全密钥的全局默认值，可被租户配置覆盖。 */
        private String amapSecurityCode = "";

        public String getDeviceRegistryKey() {
            return deviceRegistryKey;
        }

        public void setDeviceRegistryKey(String deviceRegistryKey) {
            this.deviceRegistryKey = deviceRegistryKey;
        }

        public long getExpiryScanMillis() {
            return expiryScanMillis;
        }

        public void setExpiryScanMillis(long expiryScanMillis) {
            this.expiryScanMillis = expiryScanMillis;
        }

        public String getPlatformName() {
            return platformName;
        }

        public void setPlatformName(String platformName) {
            this.platformName = platformName;
        }

        public String getAmapKey() {
            return amapKey;
        }

        public void setAmapKey(String amapKey) {
            this.amapKey = amapKey;
        }

        public String getAmapSecurityCode() {
            return amapSecurityCode;
        }

        public void setAmapSecurityCode(String amapSecurityCode) {
            this.amapSecurityCode = amapSecurityCode;
        }
    }
}
