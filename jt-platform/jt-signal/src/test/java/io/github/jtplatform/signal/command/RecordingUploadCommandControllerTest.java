package io.github.jtplatform.signal.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtplatform.common.port.RecordingUploadCredentialPort;
import io.github.jtplatform.common.port.RecordingUploadCredentialPort.RecordingUploadCredentials;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t1078.T9206;
import org.yzh.protocol.t808.T0001;
import org.yzh.web.endpoint.MessageManager;
import reactor.core.publisher.Mono;

class RecordingUploadCommandControllerTest {
    @Test
    void serverIssuesCredentialAndCallerCannotSupplyAFixedAccount() {
        MessageManager messages = mock(MessageManager.class);
        RecordingUploadCredentialPort credentials = mock(RecordingUploadCredentialPort.class);
        @SuppressWarnings("unchecked") ObjectProvider<RecordingUploadCredentialPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(credentials);
        when(credentials.issue("task-1", "device-1")).thenReturn(new RecordingUploadCredentials(
                "task-1", "192.0.2.10", 2121, "random-user", "one-time-secret", "/",
                Instant.parse("2026-08-21T00:30:00Z")));
        AtomicReference<T9206> sent = new AtomicReference<>();
        when(messages.request(eq("device-1"), any(JTMessage.class), eq(T0001.class)))
                .thenAnswer(invocation -> {
                    T9206 command = invocation.getArgument(1);
                    command.setSerialNo(77);
                    sent.set(command);
                    return Mono.just(new T0001().setResultCode(T0001.Success));
                });
        RecordingUploadCommandController controller =
                new RecordingUploadCommandController(messages, provider);

        var response = controller.upload(new RecordingUploadCommandController.UploadCommand(
                "task-1", "device-1", 1,
                LocalDateTime.parse("2026-08-21T08:00:00"),
                LocalDateTime.parse("2026-08-21T08:10:00"),
                0, 0, 3, 1, 0, 7)).block();

        assertThat(response).isNotNull();
        assertThat(response.commandSerialNo()).isEqualTo(77);
        assertThat(sent.get().getUsername()).isEqualTo("random-user");
        assertThat(sent.get().getPassword()).isEqualTo("one-time-secret");
        assertThat(sent.get().getIp()).isEqualTo("192.0.2.10");
        assertThat(sent.get().toString())
                .doesNotContain("random-user")
                .doesNotContain("one-time-secret");
        verify(credentials).bindCommand("task-1", "device-1", 77);
    }
}
