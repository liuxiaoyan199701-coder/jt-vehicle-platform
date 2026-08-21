package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.jtplatform.simulator.config.Jt808Version;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.TerminalManagementConfig;
import java.util.ArrayList;
import java.util.List;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t808.T0107;
import org.yzh.protocol.t808.T0108;
import org.yzh.protocol.t808.T8108;

class TerminalManagementProtocolTest {
    private final Jt808MessageCodec codec = new Jt808MessageCodec();

    @Test
    void terminalPropertyUsesT0107FieldWidthsAndRoundTrips() throws Exception {
        T0107 property = new T0107()
                .setDeviceType(0)
                .setMakerId("JT")
                .setDeviceModel("SIMULATOR")
                .setDeviceId("1380000")
                .setIccid("00000000000000000000")
                .setHardwareVersion("1.0")
                .setFirmwareVersion("0.1.0")
                .setGnssAttribute(1)
                .setNetworkAttribute(1);
        prepare(property, Jt808Version.V2013, "123456789012", 1);

        T0107 decoded = assertInstanceOf(T0107.class, codec.decode(codec.encode(property)));
        assertEquals("00000000000000000000", decoded.getIccid());
        assertEquals("1380000", decoded.getDeviceId());
        assertEquals("JT", decoded.getMakerId());
        assertEquals("1.0", decoded.getHardwareVersion());
        assertEquals("0.1.0", decoded.getFirmwareVersion());
    }

    @Test
    void largeUpgradePacketIsReassembledBeforeTerminalHandlesIt() throws Exception {
        byte[] firmware = new byte[2_500];
        for (int i = 0; i < firmware.length; i++) {
            firmware[i] = (byte) (i % 251);
        }
        T8108 upgrade = new T8108()
                .setType(T8108.Terminal)
                .setMakerId("JT")
                .setVersion("2.0.1")
                .setPacket(firmware);
        prepare(upgrade, Jt808Version.V2013, "123456789012", 2);

        byte[] encoded = codec.encode(upgrade);
        List<ByteBuf> frames = frames(encoded);
        try {
            T8108 reassembled = null;
            for (ByteBuf frame : frames) {
                JTMessage decoded = codec.decode(io.netty.buffer.ByteBufUtil.getBytes(frame));
                if (decoded instanceof T8108 candidate && candidate.getPacket() != null) {
                    reassembled = candidate;
                }
            }
            assertNotNull(reassembled);
            assertEquals(firmware.length, reassembled.getPacket().length);
            try (SignalClient client = new SignalClient(
                    SimulatorConfig.defaults(), noCommands(), null)) {
                assertEquals(true, client.validUpgradePacketLengthForTest(reassembled));
            }
            for (int i = 0; i < firmware.length; i++) {
                assertEquals(firmware[i], reassembled.getPacket()[i]);
            }
        } finally {
            frames.forEach(io.netty.util.ReferenceCountUtil::safeRelease);
        }
    }

    @Test
    void upgradeLengthCheckRejectsPayloadDifferentFromDeclaredBody() throws Exception {
        T8108 upgrade = new T8108()
                .setType(T8108.Terminal).setMakerId("JT").setVersion("2.0")
                .setPacket(new byte[] {1, 2, 3, 4});
        prepare(upgrade, Jt808Version.V2013, "123456789012", 5);
        byte[] encoded = codec.encode(upgrade);
        T8108 decoded = assertInstanceOf(T8108.class, codec.decode(encoded));
        try (SignalClient client = new SignalClient(
                SimulatorConfig.defaults(), noCommands(), null)) {
            assertEquals(true, client.validUpgradePacketLengthForTest(decoded));
            decoded.setPacket(new byte[] {1});
            assertEquals(false, client.validUpgradePacketLengthForTest(decoded));
        }
    }

    @Test
    void upgradeResultCodeRoundTripsAsSuccessOrFailure() throws Exception {
        T0108 success = new T0108().setType(T0108.Terminal).setResult(0);
        prepare(success, Jt808Version.V2013, "123456789012", 3);
        T0108 decodedSuccess = assertInstanceOf(T0108.class, codec.decode(codec.encode(success)));
        assertEquals(0, decodedSuccess.getResult());

        T0108 failure = new T0108().setType(T0108.Terminal).setResult(1);
        prepare(failure, Jt808Version.V2013, "123456789012", 4);
        T0108 decodedFailure = assertInstanceOf(T0108.class, codec.decode(codec.encode(failure)));
        assertEquals(1, decodedFailure.getResult());
    }

    @Test
    void oldConfigGetsDefaultTerminalManagementState() throws Exception {
        SimulatorConfig defaults = SimulatorConfig.defaults();
        String oldJson = """
                {"signalHost":"127.0.0.1","signalPort":7100,"version":"V2013",
                 "mobileNo":"138000000000","deviceId":"1380000","channel":1,
                 "registration":%s,"ffmpegPath":"","cameraName":"","microphoneName":"",
                 "mainProfile":%s,"subProfile":%s,"previewWidth":640,"previewHeight":360,
                 "previewFps":5,"maxPayloadBytes":1400,"trip":%s}
                """.formatted(
                json(defaults.registration()), json(defaults.mainProfile()), json(defaults.subProfile()),
                json(defaults.trip()));
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        SimulatorConfig loaded = mapper.readValue(oldJson, SimulatorConfig.class);
        assertEquals(TerminalManagementConfig.defaults(), loaded.terminalManagement());
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

    private static String json(Object value) throws Exception {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
    }

    private static <T extends JTMessage> T prepare(
            T message, Jt808Version version, String mobile, int serial) {
        message.setMessageId(message.reflectMessageId());
        message.setClientId(mobile);
        message.setSerialNo(serial);
        message.setProtocolVersion(version.protocolVersion());
        message.setVersion(version.versionFlag());
        message.setEncryption(0);
        message.setSubpackage(false);
        message.setReserved(false);
        return message;
    }

    private static List<ByteBuf> frames(byte[] encoded) {
        List<ByteBuf> result = new ArrayList<>();
        ByteBuf source = Unpooled.wrappedBuffer(encoded);
        int start = -1;
        for (int i = 0; i < source.writerIndex(); i++) {
            if (source.getByte(i) == 0x7e) {
                if (start < 0) {
                    start = i;
                } else if (i > start + 1) {
                    result.add(source.retainedSlice(start, i - start + 1));
                    start = i + 1;
                }
            }
        }
        return result;
    }
}
