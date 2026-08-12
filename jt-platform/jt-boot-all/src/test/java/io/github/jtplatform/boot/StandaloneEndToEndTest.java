package io.github.jtplatform.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.port.StreamRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
class StandaloneEndToEndTest {
    @LocalServerPort
    private int apiPort;

    @Autowired
    private StreamRegistry streams;

    @Test
    void terminalToPictureAndTalkbackRunsWithoutExternalDependencies() throws Exception {
        PlatformEndToEndScenario.run(new PlatformEndToEndScenario.Endpoints(
                URI.create("http://127.0.0.1:" + apiPort + '/'),
                7100,
                7811,
                7814,
                7815));
        assertTrue(awaitReleasedSubscriptions(), "standalone subscriptions were not released");
        assertEquals(2, streams.entries().size());
    }

    private boolean awaitReleasedSubscriptions() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (streams.entries().size() == 2
                    && streams.entries().stream().allMatch(entry -> entry.subscriberCount() == 0)) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return false;
    }
}
