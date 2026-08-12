package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.VideoProfile;

public record ProfileFormData(
        String width,
        String height,
        String frameRate,
        String bitrateKbps,
        String gopSeconds) {

    public ProfileFormData {
        width = normalize(width);
        height = normalize(height);
        frameRate = normalize(frameRate);
        bitrateKbps = normalize(bitrateKbps);
        gopSeconds = normalize(gopSeconds);
    }

    public static ProfileFormData from(VideoProfile profile) {
        return new ProfileFormData(
                Integer.toString(profile.width()),
                Integer.toString(profile.height()),
                Integer.toString(profile.frameRate()),
                Integer.toString(profile.bitrateKbps()),
                Integer.toString(profile.gopSeconds()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
