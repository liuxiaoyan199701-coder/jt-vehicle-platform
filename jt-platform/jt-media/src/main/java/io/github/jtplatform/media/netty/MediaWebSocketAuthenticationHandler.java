package io.github.jtplatform.media.netty;

import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.auth.TokenValidationResult;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ChannelHandler.Sharable
final class MediaWebSocketAuthenticationHandler extends ChannelInboundHandlerAdapter {
    static final AttributeKey<StreamKey> AUTHORIZED_STREAM =
            AttributeKey.valueOf(MediaWebSocketAuthenticationHandler.class, "authorizedStream");
    static final AttributeKey<String> AUTHORIZATION_FAILURE =
            AttributeKey.valueOf(MediaWebSocketAuthenticationHandler.class, "authorizationFailure");

    private final boolean authenticationEnabled;
    private final StreamTokenStore tokens;
    private final String mediaInstanceId;

    MediaWebSocketAuthenticationHandler(
            boolean authenticationEnabled,
            StreamTokenStore tokens,
            String mediaInstanceId) {
        this.authenticationEnabled = authenticationEnabled;
        this.tokens = authenticationEnabled ? Objects.requireNonNull(tokens, "tokens") : tokens;
        this.mediaInstanceId = requireText(mediaInstanceId, "mediaInstanceId");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof FullHttpRequest request) {
            authorize(context, request.uri());
        }
        super.channelRead(context, message);
    }

    private void authorize(ChannelHandlerContext context, String uri) {
        QueryStringDecoder query = new QueryStringDecoder(uri);
        Map<String, List<String>> parameters = query.parameters();
        StreamKey streamKey;
        try {
            streamKey = streamKey(parameters);
        } catch (IllegalArgumentException invalid) {
            if (authenticationEnabled || hasAnyStreamParameter(parameters)) {
                reject(context, "INVALID_STREAM_BINDING");
            }
            return;
        }

        if (!authenticationEnabled) {
            authorize(context, streamKey);
            return;
        }

        String token = first(parameters, "token");
        if (token == null || token.isBlank()) {
            reject(context, "AUTH_TOKEN_MISSING");
            return;
        }
        TokenValidationResult result = tokens.validateAndConsume(token, streamKey, mediaInstanceId);
        if (result == TokenValidationResult.VALID) {
            authorize(context, streamKey);
        } else {
            reject(context, switch (result) {
                case MISSING -> "AUTH_TOKEN_INVALID";
                case EXPIRED -> "AUTH_TOKEN_EXPIRED";
                case WRONG_STREAM -> "AUTH_TOKEN_WRONG_STREAM";
                case WRONG_INSTANCE -> "AUTH_TOKEN_WRONG_INSTANCE";
                case REPLAYED -> "AUTH_TOKEN_REPLAYED";
                case VALID -> throw new IllegalStateException("valid token reached rejection path");
            });
        }
    }

    private static StreamKey streamKey(Map<String, List<String>> parameters) {
        String deviceId = first(parameters, "deviceId");
        String channel = first(parameters, "channel");
        String streamKind = first(parameters, "streamKind");
        if (deviceId == null || channel == null || streamKind == null) {
            throw new IllegalArgumentException("stream binding is incomplete");
        }
        try {
            return new StreamKey(deviceId, Integer.parseInt(channel), StreamKind.fromWireValue(streamKind));
        } catch (NumberFormatException invalidChannel) {
            throw new IllegalArgumentException("channel is invalid", invalidChannel);
        }
    }

    private static boolean hasAnyStreamParameter(Map<String, List<String>> parameters) {
        return parameters.containsKey("deviceId")
                || parameters.containsKey("channel")
                || parameters.containsKey("streamKind");
    }

    private static String first(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static void authorize(ChannelHandlerContext context, StreamKey streamKey) {
        context.channel().attr(AUTHORIZATION_FAILURE).set(null);
        context.channel().attr(AUTHORIZED_STREAM).set(streamKey);
    }

    private static void reject(ChannelHandlerContext context, String code) {
        context.channel().attr(AUTHORIZED_STREAM).set(null);
        context.channel().attr(AUTHORIZATION_FAILURE).set(code);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
