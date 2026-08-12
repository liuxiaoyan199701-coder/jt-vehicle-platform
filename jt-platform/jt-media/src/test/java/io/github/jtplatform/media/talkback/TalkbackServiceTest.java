package io.github.jtplatform.media.talkback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.protocol.FragmentFlag;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.media.protocol.Jt1078Header;
import io.github.jtplatform.media.protocol.Jt1078RtpDecoder;
import io.github.jtplatform.media.protocol.RtpPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TalkbackServiceTest {
    private static final StreamKey STREAM = new StreamKey("13800138000", 2, StreamKind.TALKBACK);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-10T12:34:56.789Z"), ZoneOffset.UTC);

    @Test
    void exclusiveModeRejectsSecondParticipantUntilTheOwnerLeavesAndWritesDecodablePacket() {
        TalkbackProperties properties = properties(TalkbackMode.EXCLUSIVE);
        EmbeddedChannel device = new EmbeddedChannel();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();

        try (TalkbackService service = new TalkbackService(properties, CLOCK)) {
            service.registerDeviceChannel(STREAM, device, header(6));

            assertTrue(service.subscribe(STREAM, first));
            assertFalse(service.subscribe(STREAM, second));
            assertEquals(1, service.participantCount(STREAM));

            service.unsubscribe(first);
            assertTrue(service.subscribe(STREAM, second));
            byte[] audio = {(byte) 0xd5, (byte) 0x7a, 0x55};
            assertEquals(TalkbackUploadResult.ACCEPTED, service.upload(STREAM, second, audio));

            RtpPacket packet = readPacket(device);
            assertEquals(STREAM.deviceId(), packet.header().deviceId());
            assertEquals(STREAM.channel(), packet.header().channel());
            assertEquals(6, packet.header().payloadType());
            assertEquals(0, packet.header().sequence());
            assertEquals(Jt1078Constants.AUDIO_FRAME, packet.header().dataType());
            assertEquals(FragmentFlag.ATOMIC, packet.header().fragmentFlag());
            assertEquals(CLOCK.millis(), packet.header().timestamp());
            assertArrayEquals(audio, packet.payload());
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
            device.finishAndReleaseAll();
        }
    }

    @Test
    void mixModeSchedulesTwentyMillisecondTicksAndEmitsOneMixedPacketPerTick() {
        TalkbackProperties properties = properties(TalkbackMode.MIX);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        doReturn(scheduled).when(scheduler).scheduleAtFixedRate(
                tick.capture(), eq(20L), eq(20L), eq(TimeUnit.MILLISECONDS));
        EmbeddedChannel device = new EmbeddedChannel();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();

        TalkbackService service = new TalkbackService(properties, CLOCK, scheduler, false);
        try {
            service.registerDeviceChannel(STREAM, device, header(Jt1078Constants.PT_G711A));
            assertTrue(service.subscribe(STREAM, first));
            assertTrue(service.subscribe(STREAM, second));
            byte[] left = new byte[80];
            byte[] right = new byte[320];
            Arrays.fill(left, (byte) 0xd4);
            Arrays.fill(right, (byte) 0x54);
            assertEquals(TalkbackUploadResult.ACCEPTED, service.upload(STREAM, first, left));
            assertEquals(TalkbackUploadResult.ACCEPTED, service.upload(STREAM, second, right));

            tick.getValue().run();

            RtpPacket packet = readPacket(device);
            byte[] paddedLeft = new byte[160];
            Arrays.fill(paddedLeft, (byte) 0xd5);
            System.arraycopy(left, 0, paddedLeft, 0, left.length);
            assertArrayEquals(
                    G711ALaw.mix(new byte[][] {paddedLeft, Arrays.copyOfRange(right, 0, 160)}),
                    packet.payload());
            assertNull(device.readOutbound());

            tick.getValue().run();
            assertArrayEquals(Arrays.copyOfRange(right, 160, 320), readPacket(device).payload());

            tick.getValue().run();
            assertNull(device.readOutbound());
        } finally {
            service.close();
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
            device.finishAndReleaseAll();
        }

        verify(scheduled).cancel(false);
        verify(scheduler, never()).shutdown();
    }

    @Test
    void mixModeSplitsAndBoundsLargeUploadsToFixedTwentyMillisecondFrames() {
        TalkbackProperties properties = properties(TalkbackMode.MIX);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        doReturn(scheduled).when(scheduler).scheduleAtFixedRate(
                tick.capture(), eq(20L), eq(20L), eq(TimeUnit.MILLISECONDS));
        EmbeddedChannel device = new EmbeddedChannel();
        EmbeddedChannel participant = new EmbeddedChannel();

        TalkbackService service = new TalkbackService(properties, CLOCK, scheduler, false);
        try {
            service.registerDeviceChannel(STREAM, device, header(Jt1078Constants.PT_G711A));
            assertTrue(service.subscribe(STREAM, participant));
            byte[] audio = new byte[4096];
            Arrays.fill(audio, (byte) 0xd5);

            assertEquals(TalkbackUploadResult.ACCEPTED, service.upload(STREAM, participant, audio));
            tick.getValue().run();

            RtpPacket packet = readPacket(device);
            assertEquals(160, packet.payload().length);
            assertArrayEquals(Arrays.copyOf(audio, 160), packet.payload());
            assertEquals(1, service.droppedFrameCount());
        } finally {
            service.close();
            participant.finishAndReleaseAll();
            device.finishAndReleaseAll();
        }
    }

    @Test
    void failedExclusiveSwitchKeepsTheOriginalSubscriptionAndOccupancy() {
        StreamKey target = new StreamKey("13800138001", 2, StreamKind.TALKBACK);
        TalkbackProperties properties = properties(TalkbackMode.EXCLUSIVE);
        EmbeddedChannel originalDevice = new EmbeddedChannel();
        EmbeddedChannel targetDevice = new EmbeddedChannel();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();

        try (TalkbackService service = new TalkbackService(properties, CLOCK)) {
            service.registerDeviceChannel(STREAM, originalDevice, header(Jt1078Constants.PT_G711A));
            service.registerDeviceChannel(target, targetDevice, header(Jt1078Constants.PT_G711A));
            assertTrue(service.subscribe(STREAM, first));
            assertTrue(service.subscribe(target, second));

            assertFalse(service.subscribe(target, first));
            assertEquals(1, service.participantCount(STREAM));
            assertEquals(1, service.participantCount(target));
            assertEquals(TalkbackUploadResult.ACCEPTED,
                    service.upload(STREAM, first, new byte[] {(byte) 0xd5}));
            assertArrayEquals(new byte[] {(byte) 0xd5}, readPacket(originalDevice).payload());
            assertNull(targetDevice.readOutbound());
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
            originalDevice.finishAndReleaseAll();
            targetDevice.finishAndReleaseAll();
        }
    }

    @Test
    void unwritableDeviceDropsExclusiveAudioWithoutQueueingUnboundedWrites() {
        TalkbackProperties properties = properties(TalkbackMode.EXCLUSIVE);
        EmbeddedChannel device = new EmbeddedChannel();
        EmbeddedChannel participant = new EmbeddedChannel();

        try (TalkbackService service = new TalkbackService(properties, CLOCK)) {
            service.registerDeviceChannel(STREAM, device, header(Jt1078Constants.PT_G711A));
            assertTrue(service.subscribe(STREAM, participant));
            device.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            assertFalse(device.isWritable());

            assertEquals(TalkbackUploadResult.ACCEPTED,
                    service.upload(STREAM, participant, new byte[] {(byte) 0xd5}));
            device.runPendingTasks();

            assertNull(device.readOutbound());
            assertEquals(1, service.droppedFrameCount());
        } finally {
            participant.finishAndReleaseAll();
            device.finishAndReleaseAll();
        }
    }

    @Test
    void uploadRequiresAnActiveSubscriptionAndDeviceConnection() {
        TalkbackProperties properties = properties(TalkbackMode.EXCLUSIVE);
        EmbeddedChannel participant = new EmbeddedChannel();

        try (TalkbackService service = new TalkbackService(properties, CLOCK)) {
            assertEquals(TalkbackUploadResult.NOT_SUBSCRIBED,
                    service.upload(STREAM, participant, new byte[] {1}));
            assertTrue(service.subscribe(STREAM, participant));
            assertEquals(TalkbackUploadResult.DEVICE_NOT_CONNECTED,
                    service.upload(STREAM, participant, new byte[] {1}));
        } finally {
            participant.finishAndReleaseAll();
        }
    }

    private static TalkbackProperties properties(TalkbackMode mode) {
        TalkbackProperties properties = new TalkbackProperties();
        properties.setMode(mode);
        properties.setMixInterval(Duration.ofMillis(20));
        return properties;
    }

    private static Jt1078Header header(int payloadType) {
        return new Jt1078Header(
                0,
                payloadType,
                7,
                STREAM.deviceId(),
                STREAM.channel(),
                Jt1078Constants.AUDIO_FRAME,
                FragmentFlag.ATOMIC,
                0,
                0,
                0,
                1);
    }

    private static RtpPacket readPacket(EmbeddedChannel device) {
        device.runPendingTasks();
        ByteBuf encoded = device.readOutbound();
        assertNotNull(encoded);
        EmbeddedChannel decoder = new EmbeddedChannel(new Jt1078RtpDecoder(StreamKind.TALKBACK, 4096));
        try {
            assertTrue(decoder.writeInbound(encoded));
            RtpPacket packet = decoder.readInbound();
            assertNotNull(packet);
            return packet;
        } finally {
            decoder.finishAndReleaseAll();
        }
    }
}
