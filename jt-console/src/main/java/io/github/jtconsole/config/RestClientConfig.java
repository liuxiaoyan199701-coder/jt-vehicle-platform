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

    /**
     * 下行指令代理专用的客户端。与 gatewayRestClient 的区别在超时方向相反：
     * 指令是同步等待终端应答的，网关内部等 10 秒后返回 502，因此本层的读超时
     * 必须<b>大于</b> 10 秒，否则 8 秒处客户端先断开，把「设备不响应」的明确错误
     * 掩盖成笼统的连接超时。
     */
    @Bean
    RestClient commandGatewayRestClient(ConsoleProperties properties) {
        ConsoleProperties.Gateway gateway = properties.getGateway();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(gateway.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofSeconds(14));
        return RestClient.builder()
                .baseUrl(gateway.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
