package io.github.jtplatform.simulator.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulatorLogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void redactsCredentialsFromFileAndObservers() throws Exception {
        try (SimulatorLog log = new SimulatorLog(
                temporaryDirectory, 4_096, 2,
                Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC))) {
            log.info("signal", "token=secret-value payload={\"authorization\":\"bearer-secret\"}");
            String text = Files.readString(log.logFile(), StandardCharsets.UTF_8);
            assertTrue(text.contains("token=***"));
            assertTrue(text.contains("\"authorization\":\"***\""));
            assertFalse(text.contains("secret-value"));
            assertFalse(text.contains("bearer-secret"));
            assertFalse(log.recentEntries().getFirst().message().contains("secret-value"));
        }
    }

    @Test
    void rotatesBoundedLogFiles() throws Exception {
        try (SimulatorLog log = new SimulatorLog(
                temporaryDirectory, 80, 2, Clock.systemUTC())) {
            for (int index = 0; index < 20; index++) {
                log.info("test", "entry-" + index + "-" + "x".repeat(24));
            }
            assertTrue(Files.isRegularFile(log.logFile()));
            assertTrue(Files.isRegularFile(log.logFile().resolveSibling("simulator.log.1")));
            assertFalse(Files.exists(log.logFile().resolveSibling("simulator.log.3")));
        }
    }
}
