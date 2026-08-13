package io.github.jtplatform.simulator.signal;

import io.github.jtplatform.simulator.config.Jt808Version;
import io.github.jtplatform.simulator.config.RegistrationConfig;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t1078.T9102;
import org.yzh.protocol.t808.T0001;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0102;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0801;
import org.yzh.protocol.t808.T0805;
import org.yzh.protocol.t808.T8100;
import org.yzh.protocol.t808.T8801;

public final class SignalClient implements AutoCloseable {
    private final SimulatorConfig config;
    private final SignalCommandHandler commandHandler;
    private final SignalListener listener;
    private final Timing timing;
    private final DoubleSupplier jitterSource;
    private final SerialNumber serialNumbers = new SerialNumber();
    private final Jt808MessageCodec codec = new Jt808MessageCodec();
    private final PhotoCaptureHandler photoHandler;
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("jt-simulator-signal-command-", 0).factory());
    private final AtomicBoolean connectionRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    private volatile SignalState state = SignalState.DISCONNECTED;
    private volatile Thread supervisor;
    private volatile Socket openingSocket;
    private volatile Connection connection;

    public SignalClient(
            SimulatorConfig config,
            SignalCommandHandler commandHandler,
            SignalListener listener) {
        this(config, commandHandler, listener, Timing.defaults(), Math::random);
    }

    SignalClient(
            SimulatorConfig config,
            SignalCommandHandler commandHandler,
            SignalListener listener,
            Timing timing,
            DoubleSupplier jitterSource) {
        this.config = Objects.requireNonNull(config, "config");
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.listener = listener == null ? SignalListener.NOOP : listener;
        this.timing = Objects.requireNonNull(timing, "timing");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource");
        this.photoHandler = new PhotoCaptureHandler(config, this::logDiagnostic);
    }

    private void logDiagnostic(String message) {
        try {
            listener.onDiagnostic(message);
        } catch (RuntimeException ignored) {
            // 诊断回调失败不影响协议状态机
        }
    }

    public void connect() {
        if (closed.get()) {
            throw new IllegalStateException("SignalClient is closed");
        }
        connectionRequested.set(true);
        synchronized (lifecycleLock) {
            if (supervisor != null && supervisor.isAlive()) {
                return;
            }
            supervisor = Thread.ofVirtual()
                    .name("jt-simulator-signal-supervisor")
                    .start(this::supervise);
        }
    }

    public void disconnect() {
        connectionRequested.set(false);
        transition(SignalState.STOPPING, "Disconnect requested");
        closeQuietly(openingSocket);
        Connection current = connection;
        if (current != null) {
            current.close();
        }
        Thread currentSupervisor = supervisor;
        if (currentSupervisor != null) {
            currentSupervisor.interrupt();
        } else {
            transition(SignalState.DISCONNECTED, "Disconnected");
        }
    }

    public SignalState state() {
        return state;
    }

    public boolean connectionRequested() {
        return connectionRequested.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        disconnect();
        Thread currentSupervisor = supervisor;
        if (currentSupervisor != null && currentSupervisor != Thread.currentThread()) {
            try {
                currentSupervisor.join(Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        commandExecutor.shutdownNow();
    }

    private void supervise() {
        AtomicInteger failureCount = new AtomicInteger();
        boolean rejected = false;
        try {
            while (connectionRequested.get() && !closed.get()) {
                try {
                    runSingleConnection(() -> failureCount.set(0));
                    failureCount.set(0);
                } catch (SignalRejectedException rejection) {
                    reportError("registration or authentication rejected", rejection);
                    connectionRequested.set(false);
                    transition(SignalState.FAILED, rejection.getMessage());
                    rejected = true;
                    break;
                } catch (InterruptedException interrupted) {
                    if (connectionRequested.get()) {
                        reportError("signal supervisor interrupted", interrupted);
                    }
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception failure) {
                    if (connectionRequested.get()) {
                        reportError("signal connection failed", failure);
                    }
                }

                if (!connectionRequested.get() || closed.get()) {
                    break;
                }
                Duration delay = backoffDelay(failureCount.getAndIncrement());
                transition(SignalState.RECONNECT_WAIT,
                        "Reconnect in " + delay.toMillis() + " ms");
                try {
                    sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            synchronized (lifecycleLock) {
                if (supervisor == Thread.currentThread()) {
                    supervisor = null;
                }
            }
            if (!rejected) {
                transition(SignalState.DISCONNECTED, "Disconnected");
            }
            if (connectionRequested.get() && !closed.get()) {
                connect();
            }
        }
    }

    private void runSingleConnection(Runnable onAuthenticated) throws Exception {
        transition(SignalState.CONNECTING, config.signalHost() + ':' + config.signalPort());
        Socket socket = new Socket();
        openingSocket = socket;
        try {
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(config.signalHost(), config.signalPort()),
                    toTimeoutMillis(timing.connectTimeout()));
        } finally {
            openingSocket = null;
        }
        if (!connectionRequested.get()) {
            closeQuietly(socket);
            return;
        }

        Connection current = new Connection(socket, codec);
        connection = current;
        try {
            socket.setSoTimeout(toTimeoutMillis(timing.responseTimeout()));
            authenticate(current);
            onAuthenticated.run();
            socket.setSoTimeout(0);
            transition(SignalState.ONLINE, "Authenticated");
            current.startHeartbeat();
            readOnline(current);
        } finally {
            current.close();
            if (connection == current) {
                connection = null;
            }
            notifySignalDisconnected();
        }
    }

    private void authenticate(Connection current) throws Exception {
        transition(SignalState.REGISTERING, "Sending T0100");
        int registrationSerial = serialNumbers.next();
        current.writer.write(registration(registrationSerial));
        JTMessage registrationMessage = current.readerMessage();
        if (!registrationMessage.isVerified()) {
            throw new SignalProtocolException("T8100 checksum verification failed");
        }
        if (!(registrationMessage instanceof T8100 response)) {
            throw new SignalProtocolException("Expected T8100 but received message 0x"
                    + Integer.toHexString(registrationMessage.getMessageId()));
        }
        if (response.getResponseSerialNo() != registrationSerial) {
            throw new SignalProtocolException("T8100 response serial does not match T0100");
        }
        if (response.getResultCode() != T8100.Success) {
            throw new SignalRejectedException("T8100 rejected registration with result "
                    + response.getResultCode());
        }
        if (response.getToken() == null || response.getToken().isBlank()) {
            throw new SignalProtocolException("Successful T8100 did not contain an authentication token");
        }

        transition(SignalState.AUTHENTICATING, "Sending T0102");
        int authenticationSerial = serialNumbers.next();
        current.writer.write(authentication(authenticationSerial, response.getToken()));
        JTMessage authenticationMessage = current.readerMessage();
        if (!authenticationMessage.isVerified()) {
            throw new SignalProtocolException("Authentication 0x8001 checksum verification failed");
        }
        if (!(authenticationMessage instanceof T0001 authenticationResponse)
                || authenticationResponse.getMessageId() != JT808.平台通用应答) {
            throw new SignalProtocolException("Expected authentication 0x8001");
        }
        if (authenticationResponse.getResponseSerialNo() != authenticationSerial
                || authenticationResponse.getResponseMessageId() != JT808.终端鉴权) {
            throw new SignalProtocolException("Authentication 0x8001 correlation failed");
        }
        if (authenticationResponse.getResultCode() != T0001.Success) {
            throw new SignalRejectedException("Authentication rejected with result "
                    + authenticationResponse.getResultCode());
        }
    }

    private void readOnline(Connection current) throws IOException {
        while (connectionRequested.get() && connection == current && !current.closed.get()) {
            byte[] frame = current.frameReader.readFrame();
            JTMessage message;
            try {
                message = codec.decode(frame);
            } catch (InvalidJt808FrameException malformed) {
                reportError("malformed JT/T 808 frame", malformed);
                continue;
            }
            notifyMessage(message);
            if (!message.isVerified()) {
                dispatchResponse(current, message, () -> T0001.MessageError);
                continue;
            }
            if (message instanceof T9101 open && message.getMessageId() == 0x9101) {
                dispatchResponse(current, message, () -> await(commandHandler.open(open)));
            } else if (message instanceof T9102 control && message.getMessageId() == 0x9102) {
                dispatchResponse(current, message, () -> await(commandHandler.control(control)));
            } else if (message instanceof T8801 photo && message.getMessageId() == JT808.摄像头立即拍摄命令) {
                handlePhotoCommand(current, photo);
            } else if (message.getMessageId() == JT808.平台通用应答
                    || message.getMessageId() == JT808.终端注册应答) {
                // Platform responses are terminal events, not commands requiring another response.
            } else {
                dispatchResponse(current, message, () -> T0001.NotSupport);
            }
        }
    }

    /**
     * 处理 0x8801 摄像头立即拍摄命令。
     *
     * <p>应答不是通用的 T0001，而是 T0805（携带媒体 ID 列表），且照片要先经
     * 0x0801 上传——因此不走 dispatchResponse 的通用应答通道，单独成流。
     */
    private void handlePhotoCommand(Connection current, T8801 command) {
        commandExecutor.execute(() -> {
            int result;
            int[] ids = {};
            try {
                List<PhotoCaptureHandler.Photo> photos =
                        photoHandler.capture(command.getCommand(), command.getResolution());
                ids = new int[photos.size()];
                for (int index = 0; index < photos.size(); index++) {
                    uploadPhoto(current, photos.get(index), command.getChannelId());
                    ids[index] = photos.get(index).mediaId();
                }
                result = 0;
            } catch (Exception failure) {
                reportError("photo capture 0x8801", failure);
                result = 1;
            }
            try {
                T0805 response = prepare(new T0805()
                        .setResponseSerialNo(command.getSerialNo())
                        .setResult(result)
                        .setId(ids), serialNumbers.next());
                current.writer.write(response);
            } catch (IOException writeFailure) {
                reportError("writing T0805 response", writeFailure);
                current.close();
            }
        });
    }

    /**
     * 通过 0x0801 上传一张照片。
     *
     * <p>JPEG 通常远大于单包上限（消息体长度 10 位 = 1023 字节），但<b>分包必须交给
     * 编码器自动完成</b>：构建一条包含完整 JPEG 的消息，encode 时编码器自己切片、
     * 设置 packageTotal/packageNo 与分包标志（见 JTMessageEncoder 的 slices 分支）。
     * 手工设置分包字段会与编码器的头部长度计算错位，产生解码端「帧比声明长度短」
     * 的异常——这在网关侧 MultiPacketDecoderTest 的用法中有对照验证。
     */
    private void uploadPhoto(Connection current, PhotoCaptureHandler.Photo photo, int channelId)
            throws IOException {
        T0801 upload = new T0801()
                .setId(photo.mediaId())
                .setType(0)          // 0.图像
                .setFormat(0)        // 0.JPEG
                .setEvent(0)         // 平台下发指令触发
                .setChannelId(channelId)
                .setLocation(zeroLocation())
                .setPacket(io.netty.buffer.Unpooled.wrappedBuffer(photo.jpeg()));
        prepare(upload, serialNumbers.next());
        current.writer.write(upload);
    }

    /** 0x0801 要求携带 28 字节位置信息；模拟器不追踪位置，填全零合法位置 */
    private static T0200 zeroLocation() {
        return new T0200()
                .setDeviceTime(java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 0));
    }

    private void dispatchResponse(Connection current, JTMessage request, ResponseAction action) {
        commandExecutor.execute(() -> {
            int result;
            try {
                result = normalizeResult(action.execute());
            } catch (Exception failure) {
                reportError("platform command 0x" + Integer.toHexString(request.getMessageId()), failure);
                result = T0001.Failure;
            }
            if (connection != current || current.closed.get() || !connectionRequested.get()) {
                return;
            }
            try {
                current.writer.write(generalResponse(request, result));
            } catch (IOException writeFailure) {
                reportError("writing T0001 response", writeFailure);
                current.close();
            }
        });
    }

    private int await(CompletionStage<Integer> result) throws Exception {
        if (result == null) {
            throw new IllegalStateException("SignalCommandHandler returned null");
        }
        return result.toCompletableFuture().get();
    }

    private T0100 registration(int serial) {
        RegistrationConfig registration = config.registration();
        T0100 message = new T0100()
                .setProvinceId(registration.provinceId())
                .setCityId(registration.cityId())
                .setMakerId(registration.makerId())
                .setDeviceModel(registration.deviceModel())
                .setDeviceId(config.deviceId())
                .setPlateColor(registration.plateColor())
                .setPlateNo(registration.plateNo());
        return prepare(message, serial);
    }

    private T0102 authentication(int serial, String token) {
        T0102 message = new T0102().setToken(token);
        if (config.version() == Jt808Version.V2019) {
            message.setImei(config.registration().imei());
            message.setSoftwareVersion(config.registration().softwareVersion());
        }
        return prepare(message, serial);
    }

    private T0001 generalResponse(JTMessage request, int result) {
        T0001 response = new T0001()
                .setResponseSerialNo(request.getSerialNo())
                .setResponseMessageId(request.getMessageId())
                .setResultCode(result);
        response.setMessageId(JT808.终端通用应答);
        return prepare(response, serialNumbers.next());
    }

    private JTMessage heartbeat() {
        JTMessage message = new JTMessage();
        message.setMessageId(JT808.终端心跳);
        return prepare(message, serialNumbers.next());
    }

    private <T extends JTMessage> T prepare(T message, int serial) {
        if (message.getMessageId() == 0) {
            message.setMessageId(message.reflectMessageId());
        }
        message.setClientId(config.mobileNo());
        message.setSerialNo(serial);
        message.setEncryption(0);
        message.setSubpackage(false);
        message.setReserved(false);
        message.setProtocolVersion(config.version().protocolVersion());
        message.setVersion(config.version().versionFlag());
        return message;
    }

    private void notifySignalDisconnected() {
        java.util.concurrent.Future<?> cleanup;
        try {
            cleanup = commandExecutor.submit(commandHandler::onSignalDisconnected);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            if (!closed.get()) {
                reportError("scheduling media cleanup after signal disconnect", rejected);
            }
            return;
        }
        try {
            cleanup.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            boolean expectedStop = !connectionRequested.get() || closed.get();
            try {
                cleanup.get(5, TimeUnit.SECONDS);
            } catch (Exception cleanupFailure) {
                reportError("cleaning media after signal disconnect", cleanupFailure);
            } finally {
                if (!expectedStop) {
                    reportError("cleaning media after signal disconnect was interrupted", interrupted);
                }
                Thread.currentThread().interrupt();
            }
        } catch (Exception cleanupFailure) {
            reportError("cleaning media after signal disconnect", cleanupFailure);
        }
    }

    private Duration backoffDelay(int failureCount) {
        long initialMillis = timing.backoffInitial().toMillis();
        long maximumMillis = timing.backoffMaximum().toMillis();
        int shift = Math.min(Math.max(failureCount, 0), 30);
        long exponential;
        try {
            exponential = Math.multiplyExact(initialMillis, 1L << shift);
        } catch (ArithmeticException overflow) {
            exponential = maximumMillis;
        }
        long base = Math.min(exponential, maximumMillis);
        double sample = Math.max(0.0d, Math.min(1.0d, jitterSource.getAsDouble()));
        double factor = 0.8d + sample * 0.4d;
        long jittered = Math.round(base * factor);
        return Duration.ofMillis(Math.max(initialMillis, Math.min(maximumMillis, jittered)));
    }

    private void transition(SignalState next, String detail) {
        SignalState previous = state;
        if (previous == next && Objects.equals(detail, "")) {
            return;
        }
        state = next;
        try {
            listener.onStateChanged(previous, next, detail == null ? "" : detail);
        } catch (RuntimeException ignored) {
            // A GUI listener must not terminate the protocol state machine.
        }
    }

    private void notifyMessage(JTMessage message) {
        try {
            listener.onMessageReceived(message);
        } catch (RuntimeException ignored) {
            // A GUI listener must not terminate the protocol state machine.
        }
    }

    private void reportError(String context, Throwable error) {
        try {
            listener.onError(context, error);
        } catch (RuntimeException ignored) {
            // Error reporting must not recursively fail the protocol state machine.
        }
    }

    private static int normalizeResult(int result) {
        return result >= T0001.Success && result <= T0001.AlarmAck ? result : T0001.Failure;
    }

    private static int toTimeoutMillis(Duration timeout) {
        return Math.toIntExact(Math.max(1, timeout.toMillis()));
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best effort and the original failure remains authoritative.
        }
    }

    record Timing(
            Duration connectTimeout,
            Duration responseTimeout,
            Duration heartbeatInterval,
            Duration backoffInitial,
            Duration backoffMaximum) {
        Timing {
            requirePositive(connectTimeout, "connectTimeout");
            requirePositive(responseTimeout, "responseTimeout");
            requirePositive(heartbeatInterval, "heartbeatInterval");
            requirePositive(backoffInitial, "backoffInitial");
            requirePositive(backoffMaximum, "backoffMaximum");
            if (backoffMaximum.compareTo(backoffInitial) < 0) {
                throw new IllegalArgumentException("backoffMaximum must not be less than backoffInitial");
            }
        }

        static Timing defaults() {
            return new Timing(
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(30));
        }

        private static void requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    @FunctionalInterface
    private interface ResponseAction {
        int execute() throws Exception;
    }

    private final class Connection implements AutoCloseable {
        private final Socket socket;
        private final Jt808FrameReader frameReader;
        private final Jt808MessageWriter writer;
        private final AtomicBoolean closed = new AtomicBoolean();
        private ScheduledExecutorService heartbeatExecutor;

        private Connection(Socket socket, Jt808MessageCodec codec) throws IOException {
            this.socket = socket;
            this.frameReader = new Jt808FrameReader(socket.getInputStream());
            this.writer = new Jt808MessageWriter(socket.getOutputStream(), codec);
        }

        private JTMessage readerMessage() throws IOException {
            return codec.decode(frameReader.readFrame());
        }

        private void startHeartbeat() {
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("jt-simulator-heartbeat-", 0).factory());
            long intervalMillis = Math.max(1, timing.heartbeatInterval().toMillis());
            heartbeatExecutor.scheduleAtFixedRate(() -> {
                if (closed.get() || connection != this || !connectionRequested.get()) {
                    return;
                }
                try {
                    writer.write(heartbeat());
                } catch (IOException heartbeatFailure) {
                    reportError("writing T0002 heartbeat", heartbeatFailure);
                    close();
                }
            }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdownNow();
            }
            closeQuietly(socket);
        }
    }

    private static final class SignalProtocolException extends IOException {
        private SignalProtocolException(String message) {
            super(message);
        }
    }

    private static final class SignalRejectedException extends IOException {
        private SignalRejectedException(String message) {
            super(message);
        }
    }
}
