package io.github.jtconsole.gateway;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.security.JsonSecurityResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 网关设备档案接口的共享密钥校验。
 *
 * <p>密钥与投递密钥、管理员凭据、访问 token 相互独立：这条链路的调用方是网关进程而不是人，
 * 让它复用任何一个人类凭据都会把两套信任域焊死在一起。比较用常量时间算法。
 */
public final class DeviceRegistryKeyFilter extends OncePerRequestFilter {

    public static final String REGISTRY_KEY_HEADER = "X-JT-Registry-Key";
    static final String REGISTRY_PATH = "/gateway/device-registry";

    private final byte[] expectedKey;
    private final boolean enabled;
    private final JsonSecurityResponseWriter responses;

    public DeviceRegistryKeyFilter(
            ConsoleProperties properties, JsonSecurityResponseWriter responses) {
        String configured = properties.getTenancy().getDeviceRegistryKey();
        String trimmed = configured == null ? "" : configured.trim();
        this.enabled = !trimmed.isEmpty();
        this.expectedKey = trimmed.getBytes(StandardCharsets.UTF_8);
        this.responses = responses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(REGISTRY_PATH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            // 未配置密钥即视为该接口未开放；网关仍可用 allow-all / local-list 模式。
            responses.unauthorized(response);
            return;
        }
        String provided = request.getHeader(REGISTRY_KEY_HEADER);
        if (provided == null
                || !MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            responses.unauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }
}
