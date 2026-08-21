package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.concurrent.CompletionException;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.t808.T0701;

class WaybillSenderTest {
    @Test
    void encodesChineseWaybillAsUtf8AndRoundTrips() throws Exception {
        String content = "运单号：WB-2026-001\n货物：电子设备\n起点：上海";
        try (SignalClient client = new SignalClient(SimulatorConfig.defaults(), noCommands(), null)) {
            T0701 message = client.waybillForTest(content);
            assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), message.getData());

            T0701 decoded = (T0701) new Jt808MessageCodec().decode(
                    new Jt808MessageCodec().encode(message));
            assertEquals(content, new String(decoded.getData(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsBlankWaybillContentBeforeSending() {
        try (SignalClient client = new SignalClient(SimulatorConfig.defaults(), noCommands(), null)) {
            assertThrows(IllegalArgumentException.class, () -> client.waybillForTest(" \n\t"));
            assertThrows(CompletionException.class, () -> client.sendWaybill("").toCompletableFuture().join());
        }
    }

    private static SignalCommandHandler noCommands() {
        return new SignalCommandHandler() {
            @Override public java.util.concurrent.CompletionStage<Integer> open(
                    org.yzh.protocol.t1078.T9101 command) {
                return java.util.concurrent.CompletableFuture.completedFuture(0);
            }
            @Override public java.util.concurrent.CompletionStage<Integer> control(
                    org.yzh.protocol.t1078.T9102 command) {
                return java.util.concurrent.CompletableFuture.completedFuture(0);
            }
            @Override public void onSignalDisconnected() {
            }
        };
    }
}
