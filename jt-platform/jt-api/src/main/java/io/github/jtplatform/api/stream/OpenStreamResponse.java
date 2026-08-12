package io.github.jtplatform.api.stream;

public record OpenStreamResponse(
        String streamId,
        String instanceId,
        String wsUrl,
        String token,
        String state) {}
