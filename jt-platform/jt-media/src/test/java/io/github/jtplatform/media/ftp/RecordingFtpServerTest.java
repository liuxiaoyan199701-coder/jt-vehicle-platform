package io.github.jtplatform.media.ftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.jtplatform.delivery.publisher.MessagePublisher;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingFtpServerTest {
    @TempDir Path root;

    @Test
    void minaAcceptsOnlyTheIssuedCredentialAndRejectsItAfterCompletion() throws Exception {
        int controlPort = availablePort();
        int passivePort = availablePort();
        RecordingFtpProperties properties = new RecordingFtpProperties();
        properties.setBindAddress("127.0.0.1");
        properties.setAdvertisedAddress("127.0.0.1");
        properties.setPort(controlPort);
        properties.setPassivePorts(Integer.toString(passivePort));
        properties.setRoot(root);
        TemporaryFtpCredentialService credentials =
                new TemporaryFtpCredentialService(properties, Clock.systemUTC(), "127.0.0.1");
        RecordingUploadPublisher publisher = new RecordingUploadPublisher(
                mock(MessagePublisher.class), properties, Clock.systemUTC(),
                "media-test", "127.0.0.1", 7810);
        RecordingFtpServer server = new RecordingFtpServer(properties, credentials, publisher);
        var issued = credentials.issue("task-1", "device-1");
        credentials.bindCommand("task-1", "device-1", 77);
        try {
            server.start();
            assertThat(login(controlPort, issued.username(), issued.password())).startsWith("230");

            credentials.completeCommand("device-1", 77);

            assertThat(login(controlPort, issued.username(), issued.password())).startsWith("530");
        } finally {
            server.stop();
        }
    }

    private static String login(int port, String username, String password) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8))) {
            assertThat(input.readLine()).startsWith("220");
            command(output, "USER " + username);
            assertThat(input.readLine()).startsWith("331");
            command(output, "PASS " + password);
            return input.readLine();
        }
    }

    private static void command(BufferedWriter output, String command) throws Exception {
        output.write(command);
        output.write("\r\n");
        output.flush();
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
