package io.github.jtplatform.simulator.media;

import java.util.Objects;
import java.util.Optional;

public record CaptureDevices(String camera, Optional<String> microphone) {
    public CaptureDevices {
        camera = requireText(camera, "camera");
        microphone = Objects.requireNonNull(microphone, "microphone")
                .map(value -> requireText(value, "microphone"));
    }

    public static CaptureDevices videoOnly(String camera) {
        return new CaptureDevices(camera, Optional.empty());
    }

    public static CaptureDevices audioVideo(String camera, String microphone) {
        return new CaptureDevices(camera, Optional.of(microphone));
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
