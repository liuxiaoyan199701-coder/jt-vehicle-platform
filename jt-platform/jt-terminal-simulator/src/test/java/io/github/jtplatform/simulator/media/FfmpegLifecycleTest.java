package io.github.jtplatform.simulator.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FfmpegLifecycleTest {
    @Test
    void allocatesDynamicIpv4LoopbackAndAcceptsOutput() throws Exception {
        try (LoopbackOutput output = LoopbackOutput.open()) {
            assertTrue(output.port() > 0);
            assertEquals("127.0.0.1", output.target().getHost());
            CompletableFuture<Void> client = CompletableFuture.runAsync(() -> {
                try (Socket socket = new Socket(output.target().getHost(), output.target().getPort())) {
                    socket.getOutputStream().write(new byte[] {1, 2, 3});
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });

            try (Socket accepted = output.accept(Duration.ofSeconds(2))) {
                assertArrayEquals(new byte[] {1, 2, 3}, accepted.getInputStream().readNBytes(3));
            }
            client.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void drainsProcessStderrUntilExit() throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path java = javaHome.resolve("bin").resolve("java.exe");
        if (!Files.isRegularFile(java)) {
            java = javaHome.resolve("bin").resolve("java");
        }
        List<String> diagnostics = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (FfmpegProcess process = FfmpegProcess.start(List.of(java.toString(), "-version"), diagnostics::add)) {
            assertEquals(0, process.exit().get(5, TimeUnit.SECONDS));
            for (int attempt = 0; diagnostics.isEmpty() && attempt < 50; attempt++) {
                Thread.sleep(10);
            }
            assertFalse(diagnostics.isEmpty());
            assertTrue(diagnostics.stream().anyMatch(line -> line.toLowerCase().contains("version")));
        }
    }
}
