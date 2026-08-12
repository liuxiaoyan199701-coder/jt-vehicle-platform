package io.github.jtplatform.media.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LegacyMediaPortValidatorTest {
    @Test
    void derivesEveryMediaPortFromInstanceNumber() {
        var ports = new LegacyMediaPortValidator(new MockEnvironment()).validateAndResolve(2);

        assertEquals(7820, ports.management());
        assertEquals(7821, ports.main());
        assertEquals(7822, ports.sub());
        assertEquals(7823, ports.playback());
        assertEquals(7824, ports.talkback());
        assertEquals(7825, ports.websocket());
        assertEquals(7826, ports.httpFlv());
    }

    @Test
    void rejectsDeprecatedUpstreamMediaPortConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jt1078.server.tcp.main-port", "6077");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new LegacyMediaPortValidator(environment).validateAndResolve(1));

        assertTrue(failure.getMessage().contains("Deprecated media port setting"));
        assertTrue(failure.getMessage().contains("78N0-78N6"));
    }
}
