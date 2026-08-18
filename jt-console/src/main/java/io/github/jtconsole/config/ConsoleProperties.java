package io.github.jtconsole.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "jt.console")
public class ConsoleProperties {

    private Gateway gateway = new Gateway();
    private Media media = new Media();
    private Security security = new Security();
    private Broadcast broadcast = new Broadcast();
    private Operations operations = new Operations();
    private Ingest ingest = new Ingest();
    private Ai ai = new Ai();
    private Geo geo = new Geo();
    private Audit audit = new Audit();
    private Registration registration = new Registration();
    private Tenancy tenancy = new Tenancy();
    private Duration offlineTimeout = Duration.ofMinutes(5);
    private Duration eventRetention = Duration.ofHours(24);

    public Ingest getIngest() {
        return ingest;
    }

    public void setIngest(Ingest ingest) {
        this.ingest = ingest;
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public Geo getGeo() {
        return geo;
    }

    public void setGeo(Geo geo) {
        this.geo = geo;
    }

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

    public static class Ingest {
        /**
         * 单条 0x0704 批量定位报文最多处理的点数，超出部分丢弃并记日志。
         *
         * <p>整个投递是一个事务，而 SQLite 只有一把写锁：一条异常报文携带数万个点会让这个事务
         * 长时间独占写锁，阻塞所有其他投递与业务写入。宁可丢掉超出的点也不能卡死通道。
         */
        private int maxBatchPoints = 1000;

        public int getMaxBatchPoints() {
            return maxBatchPoints;
        }

        public void setMaxBatchPoints(int maxBatchPoints) {
            this.maxBatchPoints = maxBatchPoints;
        }
    }

    public static class Geo {
        /**
         * 高德「Web服务」类型的 key，用于服务端逆地理编码。
         *
         * <p>**不能**复用 {@code tenancy.amapKey}——那是 JS API 类型的 key，配合安全密钥在浏览器里
         * 用；拿它调 {@code restapi.amap.com} 会返回 {@code USERKEY_PLAT_NOMATCH}。两者是高德控制台里
         * 两种不同的 key 类型，必须分别申请。
         *
         * <p>留空表示不启用逆地理：坐标照常返回，只是不带地址，功能降级而不是报错。
         */
        private String amapWebServiceKey = "";
        /** 地址缓存条数。同一个停车点会被反复查询，缓存能大幅减少对高德配额的消耗。 */
        private int addressCacheSize = 5_000;
        private Duration requestTimeout = Duration.ofSeconds(4);

        public String getAmapWebServiceKey() {
            return amapWebServiceKey;
        }

        public void setAmapWebServiceKey(String amapWebServiceKey) {
            this.amapWebServiceKey = amapWebServiceKey;
        }

        public int getAddressCacheSize() {
            return addressCacheSize;
        }

        public void setAddressCacheSize(int addressCacheSize) {
            this.addressCacheSize = addressCacheSize;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    public static class Ai {
        /**
         * 工具调用轮数上限。
         *
         * <p>模型服务的地址、密钥与模型名不在这里——它们由 Spring AI 的 {@code spring.ai.deepseek.*}
         * 承载，功能是否启用则由 {@code spring.ai.model.chat} 决定（默认 {@code none} 即不装配模型）。
         * 刻意不再另设一个布尔开关：两个必须保持一致的开关只会制造半启用状态。
         */
        private int maxToolRounds = 8;
        /**
         * 单次对话的 SSE 存活上限。工具调用循环会发生多轮，加上模型自身的思考时间，
         * 这个值要比单轮请求宽裕得多。经 nginx 反代时它必须小于 proxy_read_timeout，
         * 否则连接会被代理先掐断，前端只看到流莫名其妙地停了。
         */
        private Duration streamTimeout = Duration.ofMinutes(5);
        private int maxOutputTokens = 2048;
        /** 历史裁剪预算，按字符近似。超出时从最旧的一问一答成对丢弃。 */
        private int historyCharBudget = 24_000;
        /**
         * 单个工具结果的字符上限。轨迹这类工具能轻易返回上千个点，不裁剪既烧钱又撑爆上下文，
         * 而模型本来也答不出「第 437 个点在哪」。
         */
        private int toolResultCharLimit = 4_000;
        /** 同时进行的对话数上限。满了直接拒绝而不是排队，避免请求堆在池里超时。 */
        private int concurrentChats = 4;
        private Duration conversationRetention = Duration.ofDays(90);
        private Duration reportRetention = Duration.ofDays(365);
        /** 简报生成时间。避开整点，且晚于昨日统计固化、错开审计清理。 */
        private String reportCron = "0 23 6 * * *";
        /** 对话留痕清理时间，同样错峰。 */
        private String cleanupCron = "0 41 3 * * *";

        private final Vision vision = new Vision();
        private final Attachment attachment = new Attachment();
        private final Briefing briefing = new Briefing();

        public Vision getVision() {
            return vision;
        }

        public Attachment getAttachment() {
            return attachment;
        }

        public Briefing getBriefing() {
            return briefing;
        }

        /**
         * 首页看板的 AI 要点。
         *
         * <p>定时预生成而不是打开首页即生成：首页会被反复打开、多人打开，每次调模型既慢又贵，
         * 而且同一租户的不同人看到不同的「今日要点」会直接削弱这块看板的可信度。
         */
        public static class Briefing {
            private boolean enabled = true;
            /**
             * 生成周期。刻意避开整点与既有清理任务的分钟数（审计 3:17、附件 4:53、
             * 幂等表整点后一小时）——同时挤在一起会让 SQLite 的写锁排队。
             */
            private String cron = "0 7 * * * *";
            /**
             * 一次最多几条要点。
             *
             * <p>五条是「一眼扫完」的上限。再多就变成又一个需要人去筛的列表，
             * 而这块看板存在的意义正是替人筛。
             */
            private int maxItems = 5;
            /** 要点保留天数。看板只看当天，历史留一段供排查。 */
            private Duration retention = Duration.ofDays(14);
            /** 是否做视觉巡检。关掉后简报照常生成，只是少了摄像头那一类发现。 */
            private boolean inspectCameras = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getCron() {
                return cron;
            }

            public void setCron(String cron) {
                this.cron = cron;
            }

            public int getMaxItems() {
                return maxItems;
            }

            public void setMaxItems(int maxItems) {
                this.maxItems = Math.max(1, maxItems);
            }

            public Duration getRetention() {
                return retention;
            }

            public void setRetention(Duration retention) {
                this.retention = retention;
            }

            public boolean isInspectCameras() {
                return inspectCameras;
            }

            public void setInspectCameras(boolean inspectCameras) {
                this.inspectCameras = inspectCameras;
            }
        }

        /**
         * 视觉模型：专门用来「看图」的旁路模型。
         *
         * <p><b>为什么单独一路而不是换掉主模型</b>：主模型（DeepSeek）没有视觉能力，但它在工具调用
         * 与中文运营语境上是选定的。与其为了看图整体换模型，不如让视觉模型当一个「看图员」——
         * 收图、出一段文字描述，描述再作为普通文本进入主模型的上下文。主模型全程不接触图像。
         *
         * <p><b>为什么不走 Spring AI</b>：它不是对话模型，是单次请求单次响应的转换器；接进
         * {@code spring.ai.*} 会与既有的 deepseek 自动装配抢 {@code ChatModel} bean，还得为一个
         * 不需要流式、不需要工具、不需要历史的调用背上整套抽象。直接用 {@code RestClient} 打
         * OpenAI 兼容的 {@code /v1/chat/completions} 更短也更好排查。
         *
         * <p>未配置 {@code apiKey} 时整条视觉链路关闭，相关工具不注册、上传接口明确拒绝——
         * 与高德 key 缺失时逆地理降级同一个思路：缺能力就少一个功能，不是报错。
         */
        public static class Vision {
            private String baseUrl = "";
            private String apiKey = "";
            private String model = "";
            /** 看一张图的耗时远高于纯文本补全，且它挡在主模型前面，超时要给够但不能没有。 */
            private Duration timeout = Duration.ofSeconds(60);
            /**
             * 单次最多送几张图。
             *
             * <p>图片按面积折算 token，一次十张能轻易压过整轮对话的预算。四张是「一次抓拍最多
             * 几张」与「一屏能看几张」的交集。
             */
            private int maxImages = 4;
            /**
             * 单张图送出前的最长边。
             *
             * <p>抓拍原图最大 D1（704×576），本就不大；这个上限主要挡用户上传的手机照片——
             * 4000 像素宽的图既烧钱又不会让识别更准。
             */
            private int maxEdgePixels = 1280;
            /** 描述的输出上限。要的是「看到了什么」，不是一篇作文。 */
            private int maxOutputTokens = 600;

            public boolean enabled() {
                return !apiKey.isBlank() && !baseUrl.isBlank() && !model.isBlank();
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
            }

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey == null ? "" : apiKey.trim();
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model == null ? "" : model.trim();
            }

            public Duration getTimeout() {
                return timeout;
            }

            public void setTimeout(Duration timeout) {
                this.timeout = timeout;
            }

            public int getMaxImages() {
                return maxImages;
            }

            public void setMaxImages(int maxImages) {
                this.maxImages = maxImages;
            }

            public int getMaxEdgePixels() {
                return maxEdgePixels;
            }

            public void setMaxEdgePixels(int maxEdgePixels) {
                this.maxEdgePixels = maxEdgePixels;
            }

            public int getMaxOutputTokens() {
                return maxOutputTokens;
            }

            public void setMaxOutputTokens(int maxOutputTokens) {
                this.maxOutputTokens = maxOutputTokens;
            }
        }

        /**
         * 对话里用户上传的图片。
         *
         * <p>存盘而不是塞进消息体：一张手机照片几 MB，base64 进 SQLite 会让
         * {@code findMessages} 的响应体直接爆掉——那正是 {@code tool_trace} 已经踩过并加了
         * 体积上限的坑。
         */
        public static class Attachment {
            /** 存储根目录。与网关的多媒体目录分开，两者生命周期和归属都不同。 */
            private Path directory = Path.of("data", "console", "ai-attachments");
            private DataSize maxSize = DataSize.ofMegabytes(8);
            /** 保留期。图片只是对话的输入，描述已落在消息里，原图不必长留。 */
            private Duration retention = Duration.ofDays(30);

            public Path getDirectory() {
                return directory;
            }

            public void setDirectory(Path directory) {
                this.directory = directory;
            }

            public DataSize getMaxSize() {
                return maxSize;
            }

            public void setMaxSize(DataSize maxSize) {
                this.maxSize = maxSize;
            }

            public Duration getRetention() {
                return retention;
            }

            public void setRetention(Duration retention) {
                this.retention = retention;
            }
        }

        public Duration getStreamTimeout() {
            return streamTimeout;
        }

        public void setStreamTimeout(Duration streamTimeout) {
            this.streamTimeout = streamTimeout;
        }

        public int getMaxToolRounds() {
            return maxToolRounds;
        }

        public void setMaxToolRounds(int maxToolRounds) {
            this.maxToolRounds = maxToolRounds;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public int getHistoryCharBudget() {
            return historyCharBudget;
        }

        public void setHistoryCharBudget(int historyCharBudget) {
            this.historyCharBudget = historyCharBudget;
        }

        public int getToolResultCharLimit() {
            return toolResultCharLimit;
        }

        public void setToolResultCharLimit(int toolResultCharLimit) {
            this.toolResultCharLimit = toolResultCharLimit;
        }

        public int getConcurrentChats() {
            return concurrentChats;
        }

        public void setConcurrentChats(int concurrentChats) {
            this.concurrentChats = concurrentChats;
        }

        public Duration getConversationRetention() {
            return conversationRetention;
        }

        public void setConversationRetention(Duration conversationRetention) {
            this.conversationRetention = conversationRetention;
        }

        public Duration getReportRetention() {
            return reportRetention;
        }

        public void setReportRetention(Duration reportRetention) {
            this.reportRetention = reportRetention;
        }

        public String getReportCron() {
            return reportCron;
        }

        public void setReportCron(String reportCron) {
            this.reportCron = reportCron;
        }

        public String getCleanupCron() {
            return cleanupCron;
        }

        public void setCleanupCron(String cleanupCron) {
            this.cleanupCron = cleanupCron;
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
