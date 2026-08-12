package io.github.jtplatform.simulator.signal;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import org.yzh.protocol.basics.JTMessage;

public final class Jt808MessageWriter {
    private final OutputStream output;
    private final Jt808MessageCodec codec;
    private final Object lock = new Object();

    public Jt808MessageWriter(OutputStream output, Jt808MessageCodec codec) {
        this.output = Objects.requireNonNull(output, "output");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public void write(JTMessage message) throws IOException {
        byte[] encoded = codec.encode(Objects.requireNonNull(message, "message"));
        synchronized (lock) {
            output.write(encoded);
            output.flush();
        }
    }
}
