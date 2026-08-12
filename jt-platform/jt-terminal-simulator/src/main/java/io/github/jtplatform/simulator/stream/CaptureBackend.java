package io.github.jtplatform.simulator.stream;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.VideoProfile;
import io.github.jtplatform.simulator.media.MediaFrame;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

interface CaptureBackend {
    CaptureHandle startPreview(SimulatorConfig config, Callbacks callbacks) throws IOException;

    CaptureHandle startCapture(
            SimulatorConfig config,
            VideoProfile profile,
            boolean includeAudio,
            Callbacks callbacks) throws IOException;

    interface CaptureHandle extends AutoCloseable {
        @Override
        void close();
    }

    record Callbacks(
            Consumer<MediaFrame> media,
            Consumer<byte[]> preview,
            Consumer<String> diagnostics,
            Consumer<Throwable> failure) {
        public Callbacks {
            Objects.requireNonNull(media, "media");
            Objects.requireNonNull(preview, "preview");
            Objects.requireNonNull(diagnostics, "diagnostics");
            Objects.requireNonNull(failure, "failure");
        }
    }
}
