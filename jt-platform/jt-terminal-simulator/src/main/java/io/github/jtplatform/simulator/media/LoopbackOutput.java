package io.github.jtplatform.simulator.media;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

public final class LoopbackOutput implements AutoCloseable {
    private final ServerSocket serverSocket;

    private LoopbackOutput(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    public static LoopbackOutput open() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress(ipv4Loopback(), 0), 1);
        return new LoopbackOutput(socket);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public URI target() {
        return URI.create("tcp://127.0.0.1:" + port());
    }

    public Socket accept(Duration timeout) throws IOException {
        Objects.requireNonNull(timeout, "timeout");
        long millis = timeout.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeout must be in range 1ms..Integer.MAX_VALUE ms");
        }
        serverSocket.setSoTimeout((int) millis);
        Socket accepted = serverSocket.accept();
        accepted.setTcpNoDelay(true);
        return accepted;
    }

    public boolean isClosed() {
        return serverSocket.isClosed();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("IPv4 loopback is unavailable", impossible);
        }
    }
}
