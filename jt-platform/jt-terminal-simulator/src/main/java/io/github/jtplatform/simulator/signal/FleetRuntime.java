package io.github.jtplatform.simulator.signal;

import io.github.jtplatform.simulator.config.FleetConfig;
import io.github.jtplatform.simulator.config.FleetIdentityDeriver;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.trip.RoutePlanner;
import io.github.jtplatform.simulator.trip.TripController;
import io.github.jtplatform.simulator.trip.TripViewState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 多设备生命周期协调器；成员异常被隔离在对应槽位。 */
public final class FleetRuntime implements AutoCloseable {
    private final ExecutorService lifecycle;
    private final Supplier<SignalClientFactory> factorySupplier;
    private final SignalCommandHandler sharedCommandHandler;
    private final Consumer<FleetMemberState> stateListener;
    private final List<Member> members = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long generation;
    private volatile int selectedIndex;

    public FleetRuntime(Consumer<FleetMemberState> stateListener) {
        this(stateListener, noOpCommands(), () -> SignalClientFactory.DEFAULT);
    }

    public FleetRuntime(Consumer<FleetMemberState> stateListener, SignalCommandHandler commandHandler) {
        this(stateListener, commandHandler, () -> SignalClientFactory.DEFAULT);
    }

    FleetRuntime(Consumer<FleetMemberState> stateListener, Supplier<SignalClientFactory> factorySupplier) {
        this(stateListener, noOpCommands(), factorySupplier);
    }

    private FleetRuntime(Consumer<FleetMemberState> stateListener,
            SignalCommandHandler commandHandler, Supplier<SignalClientFactory> factorySupplier) {
        this.stateListener = stateListener == null ? ignored -> { } : stateListener;
        this.sharedCommandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.factorySupplier = Objects.requireNonNull(factorySupplier, "factorySupplier");
        this.lifecycle = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jt-simulator-fleet-lifecycle-", 0).factory());
    }

    public synchronized void configure(SimulatorConfig template, FleetConfig config) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(config, "config");
        List<Member> previous = List.copyOf(members);
        generation++;
        previous.forEach(member -> {
            member.active = false;
            member.runningRequested.set(false);
            stopOneNow(member);
        });
        members.clear();
        long currentGeneration = generation;
        for (int index = 0; index < config.vehicleCount(); index++) {
            SimulatorConfig memberConfig = FleetIdentityDeriver.derive(template, config, index);
            members.add(new Member(currentGeneration, index, memberConfig, FleetSchedule.forIndex(
                    index, config.departureIntervalSeconds())));
        }
        publishAll();
    }

    public synchronized List<FleetMemberState> states() {
        return members.stream().map(Member::state).toList();
    }

    public void select(int index) {
        member(index);
        selectedIndex = index;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public void enterBlindspot() {
        SignalClient current = member(selectedIndex).client;
        if (current != null) current.enterBlindspot();
    }

    public void leaveBlindspot() {
        SignalClient current = member(selectedIndex).client;
        if (current != null) current.leaveBlindspot();
    }

    public int blindspotCachedCount() {
        SignalClient current = member(selectedIndex).client;
        return current == null ? 0 : current.blindspotCachedCount();
    }

    public void setAlarm(int bit, boolean enabled) {
        SignalClient current = member(selectedIndex).client;
        if (current != null) {
            current.setAlarm(bit, enabled);
        }
    }

    public void clearAlarms() {
        SignalClient current = member(selectedIndex).client;
        if (current != null) {
            current.clearAlarms();
        }
    }

    public void setOverspeedKph(double speedKph) {
        SignalClient current = member(selectedIndex).client;
        if (current != null) {
            current.setOverspeedKph(speedKph);
        }
    }

    public java.util.concurrent.CompletionStage<Void> sendDriverCard(
            io.github.jtplatform.simulator.config.DriverConfig driver,
            SignalClient.DriverAction action) {
        SignalClient current = member(selectedIndex).client;
        return current == null
                ? java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("selected fleet vehicle is not online"))
                : current.sendDriverCard(driver, action);
    }

    public void startAll() {
        List<Member> snapshot;
        synchronized (this) {
            ensureOpen();
            snapshot = List.copyOf(members);
            snapshot.forEach(member -> member.runningRequested.set(true));
        }
        snapshot.forEach(member -> lifecycle.execute(() -> startOne(member)));
    }

    public void stopAll() {
        List<Member> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(members);
            snapshot.forEach(member -> member.runningRequested.set(false));
        }
        snapshot.forEach(member -> lifecycle.execute(() -> stopOneNow(member)));
    }

    public void startOne(int index) {
        Member member = member(index);
        member.runningRequested.set(true);
        lifecycle.execute(() -> startOne(member));
    }

    public void stopOne(int index) {
        Member member = member(index);
        member.runningRequested.set(false);
        lifecycle.execute(() -> stopOneNow(member));
    }

    private void startOne(Member member) {
        try {
            if (!member.active || !member.runningRequested.get()) {
                return;
            }
            if (!member.schedule.departureDelay().isZero()) {
                Thread.sleep(member.schedule.departureDelay());
            }
            if (!member.active || !member.runningRequested.get()) {
                return;
            }
            SignalClient client = factorySupplier.get().create(member.config, member.index,
                    member.trip, sharedCommandHandler, detail -> update(member, detail),
                    fix -> update(member, fix));
            if (!member.active || !member.runningRequested.get()) {
                client.close();
                return;
            }
            member.client = client;
            update(member, "连接中");
            client.connect();
            member.trip.start();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            update(member, "已取消");
        } catch (RuntimeException failure) {
            // 单台启动失败只进入该台 FAILED 槽位；其它成员继续运行。
            member.client = null;
            update(member, "失败: " + message(failure));
        }
    }

    private void stopOneNow(Member member) {
        SignalClient client = member.client;
        member.client = null;
        member.trip.stop();
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException failure) {
                update(member, "断开失败: " + message(failure));
                return;
            }
        }
        update(member, "已断开");
    }

    private void update(Member member, String detail) {
        member.detail = detail == null ? "" : detail;
        publish(member.state());
    }

    private void update(Member member, LocationFix fix) {
        member.lastFix = fix;
        publish(member.state());
    }

    private void publishAll() {
        members.forEach(member -> publish(member.state()));
    }

    private void publish(FleetMemberState state) {
        try {
            stateListener.accept(state);
        } catch (RuntimeException ignored) {
            // 表格刷新失败不得影响其它车的生命周期。
        }
    }

    private synchronized Member member(int index) {
        if (index < 0 || index >= members.size()) {
            throw new IndexOutOfBoundsException("fleet index out of range: " + index);
        }
        return members.get(index);
    }

    private static SignalCommandHandler noOpCommands() {
        return new SignalCommandHandler() {
            @Override public java.util.concurrent.CompletionStage<Integer> open(
                    org.yzh.protocol.t1078.T9101 command) {
                return java.util.concurrent.CompletableFuture.completedFuture(0);
            }
            @Override public java.util.concurrent.CompletionStage<Integer> control(
                    org.yzh.protocol.t1078.T9102 command) {
                return java.util.concurrent.CompletableFuture.completedFuture(0);
            }
            @Override public void onSignalDisconnected() {
            }
        };
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("fleet runtime is closed");
        }
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Member> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(members);
            snapshot.forEach(member -> {
                member.active = false;
                member.runningRequested.set(false);
            });
        }
        snapshot.forEach(this::stopOneNow);
        lifecycle.shutdownNow();
    }

    @FunctionalInterface
    interface SignalClientFactory {
        SignalClient create(SimulatorConfig config, int index, LocationSource locationSource,
                SignalCommandHandler commandHandler, Consumer<String> stateCallback,
                Consumer<LocationFix> locationCallback);

        SignalClientFactory DEFAULT = (config, index, locationSource, commandHandler, callback,
                locationCallback) -> new SignalClient(
                config,
                commandHandler == null ? new SignalCommandHandler() {
                    @Override public java.util.concurrent.CompletionStage<Integer> open(
                            org.yzh.protocol.t1078.T9101 command) {
                        return java.util.concurrent.CompletableFuture.completedFuture(0);
                    }
                    @Override public java.util.concurrent.CompletionStage<Integer> control(
                            org.yzh.protocol.t1078.T9102 command) {
                        return java.util.concurrent.CompletableFuture.completedFuture(0);
                    }
                    @Override public void onSignalDisconnected() {
                    }
                } : commandHandler,
                new SignalListener() {
                    @Override public void onStateChanged(SignalState previous, SignalState current,
                            String detail) {
                        callback.accept(current + (detail.isBlank() ? "" : ": " + detail));
                    }
                    @Override public void onLocationFix(LocationFix fix) {
                        locationCallback.accept(fix);
                    }
                }, locationSource);
    }

    private static final class Member {
        private final long generation;
        private final int index;
        private final SimulatorConfig config;
        private final FleetSchedule schedule;
        private final TripController trip;
        private final AtomicBoolean runningRequested = new AtomicBoolean();
        private volatile boolean active = true;
        private volatile SignalClient client;
        private volatile String detail = "未启动";
        private volatile LocationFix lastFix;

        private Member(long generation, int index, SimulatorConfig config, FleetSchedule schedule) {
            this.generation = generation;
            this.index = index;
            this.config = config;
            this.schedule = schedule;
            this.trip = new TripController(new RoutePlanner(), state -> { });
            this.trip.configure(config.trip());
        }

        private FleetMemberState state() {
            SignalClient current = client;
            return new FleetMemberState(index, config.deviceId(), config.mobileNo(),
                    config.registration().plateNo(), current == null ? SignalState.DISCONNECTED : current.state(),
                    detail, schedule,
                    lastFix == null ? 0 : lastFix.latitude(),
                    lastFix == null ? 0 : lastFix.longitude(),
                    lastFix == null ? 0 : lastFix.speedKph(),
                    current == null ? 0 : current.alarmBits());
        }
    }

    public record FleetMemberState(
            int index,
            String deviceId,
            String mobileNo,
            String plateNo,
            SignalState signalState,
            String detail,
            FleetSchedule schedule,
            double latitude,
            double longitude,
            double speedKph,
            int alarmBits) {
    }
}
