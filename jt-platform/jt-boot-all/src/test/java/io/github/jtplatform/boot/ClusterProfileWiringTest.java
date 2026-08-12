package io.github.jtplatform.boot;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.jtplatform.common.port.HttpStreamCommandPort;
import io.github.jtplatform.common.port.StreamCommandPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = JtPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "jt.media.server.enabled=false")
@ActiveProfiles("cluster")
class ClusterProfileWiringTest {
    @Autowired
    private StreamCommandPort commandPort;

    @Test
    void clusterUsesHttpCommandPort() {
        assertInstanceOf(HttpStreamCommandPort.class, commandPort);
    }
}
