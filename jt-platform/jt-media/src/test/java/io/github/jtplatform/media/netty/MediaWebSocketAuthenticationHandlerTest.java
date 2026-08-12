package io.github.jtplatform.media.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.jtplatform.common.auth.InMemoryStreamTokenStore;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MediaWebSocketAuthenticationHandlerTest {
    private static final StreamKey STREAM = new StreamKey("device-1", 1, StreamKind.MAIN);
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @AfterEach
    void releaseChannels() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    }

    @Test
    void validTokenAuthorizesExactlyOneConnection() {
        InMemoryStreamTokenStore tokens = tokens(Clock.systemUTC());
        String token = tokens.issue(STREAM, "media-1", Duration.ofMinutes(1));
        MediaWebSocketAuthenticationHandler handler = handler(true, tokens, "media-1");

        EmbeddedChannel first = request(handler, uri(STREAM, token));
        EmbeddedChannel replay = request(handler, uri(STREAM, token));

        assertEquals(STREAM, first.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).get());
        assertNull(first.attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE).get());
        assertEquals("AUTH_TOKEN_REPLAYED",
                replay.attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE).get());
        assertNull(replay.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).get());
    }

    @Test
    void missingAndForgedTokensAreRejected() {
        InMemoryStreamTokenStore tokens = tokens(Clock.systemUTC());
        MediaWebSocketAuthenticationHandler handler = handler(true, tokens, "media-1");

        EmbeddedChannel missing = request(handler, uri(STREAM, null));
        EmbeddedChannel forged = request(handler, uri(STREAM, "forged"));

        assertEquals("AUTH_TOKEN_MISSING",
                missing.attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE).get());
        assertEquals("AUTH_TOKEN_INVALID",
                forged.attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE).get());
    }

    @Test
    void expiredCrossStreamAndCrossInstanceTokensAreRejected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        InMemoryStreamTokenStore tokens = tokens(clock);
        String crossBoundToken = tokens.issue(STREAM, "media-1", Duration.ofMinutes(1));

        EmbeddedChannel crossStream = request(handler(true, tokens, "media-1"),
                uri(new StreamKey("device-1", 2, StreamKind.MAIN), crossBoundToken));
        EmbeddedChannel crossInstance = request(handler(true, tokens, "media-2"),
                uri(STREAM, crossBoundToken));
        assertEquals("AUTH_TOKEN_WRONG_STREAM",
                crossStream.attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE).get());
        assertEquals("AUTH_TOKEN_WRONG_INSTANCE",
                crossInstance.attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE).get());

        String expiringToken = tokens.issue(STREAM, "media-1", Duration.ofSeconds(1));
        clock.set(Instant.parse("2026-08-10T00:00:02Z"));
        EmbeddedChannel expired = request(handler(true, tokens, "media-1"), uri(STREAM, expiringToken));
        assertEquals("AUTH_TOKEN_EXPIRED",
                expired.attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE).get());
    }

    @Test
    void disabledAuthenticationAllowsUncredentialedConnections() {
        MediaWebSocketAuthenticationHandler handler = handler(false, null, "media-1");

        EmbeddedChannel unbound = request(handler, "/ws");
        EmbeddedChannel bound = request(handler, uri(STREAM, null));

        assertNull(unbound.attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE).get());
        assertNull(unbound.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).get());
        assertEquals(STREAM, bound.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).get());
    }

    private static MediaWebSocketAuthenticationHandler handler(
            boolean enabled,
            InMemoryStreamTokenStore tokens,
            String instanceId) {
        return new MediaWebSocketAuthenticationHandler(enabled, tokens, instanceId);
    }

    private static InMemoryStreamTokenStore tokens(Clock clock) {
        return new InMemoryStreamTokenStore(new SecureRandom(), clock);
    }

    private EmbeddedChannel request(MediaWebSocketAuthenticationHandler handler, String uri) {
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channels.add(channel);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        channel.writeInbound(request);
        ReferenceCountUtil.safeRelease(channel.readInbound());
        return channel;
    }

    private static String uri(StreamKey streamKey, String token) {
        String value = "/ws?deviceId=" + streamKey.deviceId()
                + "&channel=" + streamKey.channel()
                + "&streamKind=" + streamKey.streamKind().wireValue();
        return token == null ? value : value + "&token=" + token;
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
