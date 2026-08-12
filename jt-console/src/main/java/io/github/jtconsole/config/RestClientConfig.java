package io.github.jtconsole.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * 访问 jt-platform 网关的客户端。超时必须给死：开流会等待终端响应，
     * 网关侧 {@code MessageManager} 的等待上限是 10 秒，这里配得比它略短，
     * 让超时在本层暴露而不是把前端请求一直挂住。
     */
    @Bean
    RestClient gatewayRestClient(ConsoleProperties properties) {
        ConsoleProperties.Gateway gateway = properties.getGateway();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(gateway.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(gateway.getRequestTimeout());
        return RestClient.builder()
                .baseUrl(gateway.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
