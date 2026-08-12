package io.github.jtplatform.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.SignalPorts;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ClusterEndToEndTest {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final String MOBILE_NO = "138000000000";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    private Path workingDirectory;

    @Test
    @Timeout(75)
    void separateSignalMediaAndApiProcessesRunTheStandaloneScenario() throws Exception {
        PortGroup ports = PortGroup.findAvailable();
        int apiPort = freeTcpPort();

        try (ChildProcess signal = start("signal", List.of(
                "--jt.runtime.role=signal",
                "--jt.instance.number=" + ports.instanceNumber(),
                "--jt.signal.instance-id=signal-" + ports.instanceNumber(),
                "--server.port=" + ports.signal().command(),
                "--management.server.port=" + ports.signal().management()));
             ChildProcess api = start("api", List.of(
                     "--jt.runtime.role=api",
                     "--server.port=" + apiPort,
                     "--management.server.port=0",
                     "--jt.signal.command-base-url=http://127.0.0.1:" + ports.signal().command()));) {
            awaitHttp(signal, URI.create(
                    "http://127.0.0.1:" + ports.signal().management() + "/actuator/health"));
            awaitHttp(api, URI.create(
                    "http://127.0.0.1:" + apiPort + "/internal/cluster-state/health"));

            try (ChildProcess media = start("media", List.of(
                    "--jt.runtime.role=media",
                    "--jt.instance.number=" + ports.instanceNumber(),
                    "--jt.media.instance-id=media-" + ports.instanceNumber(),
                    "--jt.cluster.api-base-url=http://127.0.0.1:" + apiPort,
                    "--jt.media.reachable-address.source=static",
                    "--jt.media.reachable-address.value=127.0.0.1",
                    "--jt.media.heartbeat-interval=1s",
                    "--jt.media.heartbeat-ttl=5s",
                    "--jt.media.server.worker-threads=2"))) {
                awaitHttp(media, URI.create(
                        "http://127.0.0.1:" + ports.media().management() + "/health"));
                try {
                    PlatformEndToEndScenario.run(new PlatformEndToEndScenario.Endpoints(
                            URI.create("http://127.0.0.1:" + apiPort + '/'),
                            ports.signal().tcp(),
                            ports.media().main(),
                            ports.media().talkback(),
                            ports.media().websocket()));
                    awaitSubscriberCount(apiPort, "main", 0);
                    awaitSubscriberCount(apiPort, "talkback", 0);
                } catch (Throwable failure) {
                    throw new AssertionError("Cluster scenario failed.\n"
                            + signal.diagnostics() + api.diagnostics() + media.diagnostics(), failure);
                }
            }
        }
    }

    private ChildProcess start(String role, List<String> applicationArguments) throws IOException {
        Path processDirectory = Files.createDirectories(workingDirectory.resolve(role));
        Path log = processDirectory.resolve(role + ".log");
        String executableName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executableName);
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Xms32m");
        command.add("-Xmx256m");
        command.add("-Dfile.encoding=UTF-8");
        command.add(JtPlatformApplication.class.getName());
        command.add("--spring.main.banner-mode=off");
        command.addAll(applicationArguments);

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(processDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("CLASSPATH", testClasspath());
        return new ChildProcess(role, builder.start(), log);
    }

    private static String testClasspath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private static void awaitHttp(ChildProcess process, URI uri) throws Exception {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail(process.role() + " process exited during startup.\n" + process.diagnostics());
            }
            try {
                HttpResponse<String> response = HTTP.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(1)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return;
                }
                lastFailure = new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            } catch (IOException failure) {
                lastFailure = failure;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + uri + ".\n" + process.diagnostics(), lastFailure);
    }

    private static void awaitSubscriberCount(int apiPort, String streamKind, int expected) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + apiPort + "/internal/cluster-state/streams/find");
        String body = JSON.writeValueAsString(Map.of("streamKey", Map.of(
                "deviceId", MOBILE_NO,
                "channel", 1,
                "streamKind", streamKind)));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Integer actual = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(1))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = JSON.readValue(response.body(), Map.class);
                Object entryValue = result.get("entry");
                if (entryValue instanceof Map<?, ?> entry
                        && entry.get("subscriberCount") instanceof Number count) {
                    actual = count.intValue();
                    if (actual == expected) {
                        return;
                    }
                }
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertEquals(expected, actual, "API-owned subscriber count for " + streamKind);
    }

    private static int freeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    private record PortGroup(int instanceNumber, SignalPorts signal, MediaPorts media) {
        static PortGroup findAvailable() throws IOException {
            for (int instance = 1; instance <= 9; instance++) {
                SignalPorts signal = SignalPorts.forInstance(instance);
                MediaPorts media = MediaPorts.forInstance(instance);
                if (available(signal, media)) {
                    return new PortGroup(instance, signal, media);
                }
            }
            throw new IOException("No free JT Platform instance port group is available");
        }

        private static boolean available(SignalPorts signal, MediaPorts media) {
            List<ServerSocket> tcpSockets = new ArrayList<>();
            try (DatagramSocket udp = new DatagramSocket(null)) {
                int[] tcpPorts = {
                        signal.management(), signal.tcp(), signal.command(),
                        media.management(), media.main(), media.sub(), media.playback(),
                        media.talkback(), media.websocket()
                };
                for (int port : tcpPorts) {
                    ServerSocket socket = new ServerSocket();
                    socket.setReuseAddress(false);
                    socket.bind(new InetSocketAddress((InetAddress) null, port));
                    tcpSockets.add(socket);
                }
                udp.setReuseAddress(false);
                udp.bind(new InetSocketAddress((InetAddress) null, signal.udp()));
                return true;
            } catch (IOException unavailable) {
                return false;
            } finally {
                tcpSockets.forEach(socket -> {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Best-effort probe cleanup.
                    }
                });
            }
        }
    }

    private record ChildProcess(String role, Process process, Path log) implements AutoCloseable {
        boolean isAlive() {
            return process.isAlive();
        }

        String diagnostics() {
            try {
                List<String> lines = Files.readAllLines(log);
                int start = Math.max(0, lines.size() - 160);
                return "--- " + role + " process ---\n"
                        + String.join(System.lineSeparator(), lines.subList(start, lines.size())) + '\n';
            } catch (IOException readFailure) {
                return "--- " + role + " process log unavailable: " + readFailure + " ---\n";
            }
        }

        @Override
        public void close() {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
