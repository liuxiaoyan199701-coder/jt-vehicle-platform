package io.github.jtplatform.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.jtplatform.boot.config.JtPlatformProperties;
import io.github.jtplatform.common.port.LocalStreamCommandPort;
import io.github.jtplatform.common.port.StreamCommandPort;
import io.github.jtplatform.signal.command.SignalStreamCommandController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = JtPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "jt.media.reachable-address.source=static",
                "jt.media.reachable-address.value=127.0.0.1"
        })
@ActiveProfiles("standalone")
class ZeroDependencyStartupTest {
    @Autowired
    private StreamCommandPort commandPort;

    @Autowired
    private JtPlatformProperties properties;

    @Autowired
    private SignalStreamCommandController internalCommandController;

    @Autowired
    private RecordingMetricsController recordingMetricsController;

    @Test
    void startsWithMemoryDefaultsAndNoExternalConnections() {
        assertInstanceOf(LocalStreamCommandPort.class, commandPort);
        assertEquals(JtPlatformProperties.RegistryType.MEMORY, properties.getRegistry().getType());
        assertEquals(JtPlatformProperties.StreamAuthMode.DISABLED, properties.getAuth().getStream().getMode());
        assertInstanceOf(SignalStreamCommandController.class, internalCommandController);
        // 控制台固定经 8100 网关基址代理 /metrics/recording；必须确保该控制器实际注册，
        // 不能只在媒体节点 78N0 管理端口存在同名端点。
        assertInstanceOf(RecordingMetricsController.class, recordingMetricsController);
    }
}
