package io.github.jtconsole.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedAddressIntegrationTest {

    private static final String DATABASE_URL =
            "jdbc:sqlite:file:forwarded-address-" + UUID.randomUUID()
                    + "?mode=memory&cache=shared";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("jt.console.security.deployment-mode", () -> "true");
        registry.add("jt.console.security.admin-username", () -> "admin");
        registry.add("jt.console.security.admin-password-hash", () ->
                new BCryptPasswordEncoder().encode("forwarded-address-password"));
        registry.add("jt.console.security.ingest-key", () ->
                "forwarded-address-ingest-key-with-at-least-32-bytes");
        registry.add("jt.console.security.rate-limit.max-failures", () -> "1");
        registry.add("jt.console.security.rate-limit.window", () -> "1m");
        registry.add("jt.console.security.rate-limit.block-duration", () -> "5m");
    }

    @Test
    void loopbackProxyCanSupplyTheRateLimitSource() throws Exception {
        assertThat(loginFrom("127.0.0.1", "198.51.100.10", "proxy-source-a"))
                .isEqualTo(401);
        assertThat(loginFrom("127.0.0.1", "198.51.100.11", "proxy-source-b"))
                .isEqualTo(401);
        assertThat(loginFrom("127.0.0.1", "198.51.100.10", "proxy-source-a-again"))
                .isEqualTo(429);
    }

    @Test
    void nonProxySourceCannotEvadeRateLimitsWithForwardedFor() throws Exception {
        assertThat(loginFrom("127.0.0.2", "198.51.100.20", "direct-source-a"))
                .isEqualTo(401);
        assertThat(loginFrom("127.0.0.2", "198.51.100.21", "direct-source-b"))
                .isEqualTo(429);
    }

    private int loginFrom(String localAddress, String forwardedFor, String username)
            throws IOException {
        byte[] body = ("{\"userName\":\"" + username + "\",\"password\":\"wrong\"}")
                .getBytes(StandardCharsets.UTF_8);
        String headers = "POST /api/auth/login HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "X-Forwarded-For: " + forwardedFor + "\r\n"
                + "Connection: close\r\n\r\n";

        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName(localAddress), 0));
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();

            String response = new String(
                    socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            String statusLine = response.substring(0, response.indexOf("\r\n"));
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
