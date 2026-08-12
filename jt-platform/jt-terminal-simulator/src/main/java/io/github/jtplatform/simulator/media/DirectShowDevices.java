package io.github.jtplatform.simulator.media;

import java.util.List;

public record DirectShowDevices(
        List<DirectShowDevice> videoDevices,
        List<DirectShowDevice> audioDevices,
        String diagnostics) {

    public DirectShowDevices {
        videoDevices = List.copyOf(videoDevices);
        audioDevices = List.copyOf(audioDevices);
        diagnostics = diagnostics == null ? "" : diagnostics;
    }
}
