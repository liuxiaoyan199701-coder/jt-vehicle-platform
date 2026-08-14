package io.github.jtconsole.gateway;

import io.github.jtconsole.config.ConsoleProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 租户失效时请求网关断开其全部设备连接。
 *
 * <p>断连只是加速手段：真正的拒绝发生在网关的 remote-api 设备鉴权侧，
 * 缓存过期后重连一律被拒。因此这里的失败绝不能阻塞停用操作本身——
 * 让网关不可达把「停用租户」整个卡住，比晚几十秒断连危险得多。
 */
@Component
public class DeviceDisconnectClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceDisconnectClient.class);
    /** 一次请求携带的设备上限，避免超大租户把请求体撑爆。 */
    private static final int BATCH_SIZE = 200;

    private final RestClient gateway;
    private final String sharedKey;
    private final AtomicLong failures = new AtomicLong();

    public DeviceDisconnectClient(RestClient gatewayRestClient, ConsoleProperties properties) {
        this.gateway = gatewayRestClient;
        this.sharedKey = properties.getTenancy().getDeviceRegistryKey() == null
                ? ""
                : properties.getTenancy().getDeviceRegistryKey().trim();
    }

    /**
     * 尽力断开这批设备。任何失败都只记录并计数，不向调用方抛出。
     *
     * @return 成功提交断连请求的设备数
     */
    public int disconnectQuietly(List<String> deviceIds, String tenantCode) {
        if (deviceIds.isEmpty()) {
            return 0;
        }
        if (sharedKey.isEmpty()) {
            LOGGER.debug("未配置网关共享密钥，跳过租户 {} 的设备断连", tenantCode);
            return 0;
        }

        int submitted = 0;
        for (int start = 0; start < deviceIds.size(); start += BATCH_SIZE) {
            List<String> batch = deviceIds.subList(
                    start, Math.min(start + BATCH_SIZE, deviceIds.size()));
            try {
                gateway.post()
                        .uri("/internal/devices/disconnect")
                        .header(DeviceRegistryKeyFilter.REGISTRY_KEY_HEADER, sharedKey)
                        .body(Map.of("clientIds", batch, "reason", "tenant-inactive"))
                        .retrieve()
                        .toBodilessEntity();
                submitted += batch.size();
            } catch (RuntimeException failure) {
                long total = failures.incrementAndGet();
                LOGGER.warn("租户 {} 的设备断连请求失败（累计 {} 次）：{}",
                        tenantCode, total, failure.getClass().getSimpleName());
            }
        }
        if (submitted > 0) {
            LOGGER.info("已请求网关断开租户 {} 的 {} 台设备", tenantCode, submitted);
        }
        return submitted;
    }

    public long failureCount() {
        return failures.get();
    }
}
