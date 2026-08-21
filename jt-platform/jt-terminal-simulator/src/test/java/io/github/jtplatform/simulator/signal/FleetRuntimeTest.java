package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.FleetConfig;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FleetRuntimeTest {
    @Test
    void scheduleOffsetsDepartureAndRouteStartByIndex() {
        assertEquals(Duration.ZERO, FleetSchedule.forIndex(0, 15).departureDelay());
        assertEquals(Duration.ofSeconds(30), FleetSchedule.forIndex(2, 15).departureDelay());
        assertEquals(200.0, FleetSchedule.forIndex(2, 15).routeStartOffsetMeters());
    }

    @Test
    void reconfigureStopsOldMembersBeforeReplacingTheFleet() throws Exception {
        List<FleetRuntime.FleetMemberState> states = new CopyOnWriteArrayList<>();
        try (FleetRuntime runtime = new FleetRuntime(states::add)) {
            runtime.configure(SimulatorConfig.defaults(), new FleetConfig(true, 2, true, true, true, 0));
            runtime.startAll();
            runtime.configure(SimulatorConfig.defaults(), new FleetConfig(true, 1, true, true, true, 0));
            assertEquals(1, runtime.states().size());
            assertEquals("1380000", runtime.states().getFirst().deviceId());
        }
    }

    @Test
    void oneMemberFactoryFailureDoesNotPreventOtherMembersFromStarting() throws Exception {
        List<FleetRuntime.FleetMemberState> states = new CopyOnWriteArrayList<>();
        AtomicInteger creations = new AtomicInteger();
        FleetRuntime.SignalClientFactory factory = (config, index, locationSource, commandHandler,
                callback, locationCallback) -> {
            if (index == 1) {
                throw new IllegalStateException("simulated member failure");
            }
            return new SignalClient(config, commandHandler, null, locationSource);
        };
        try (FleetRuntime runtime = new FleetRuntime(states::add, () -> factory)) {
            SimulatorConfig template = SimulatorConfig.defaults();
            FleetConfig config = new FleetConfig(true, 3, true, true, true, 0);
            runtime.configure(template, config);
            runtime.startAll();
            Thread.sleep(200);
            assertEquals(3, runtime.states().size());
            assertTrue(states.stream().anyMatch(state -> state.index() == 1
                    && state.detail().contains("失败")));
            assertTrue(states.stream().anyMatch(state -> state.index() == 0));
            assertTrue(states.stream().anyMatch(state -> state.index() == 2));
        }
    }

    private static final class NoopCommands implements SignalCommandHandler {
        @Override public java.util.concurrent.CompletionStage<Integer> open(org.yzh.protocol.t1078.T9101 command) {
            return java.util.concurrent.CompletableFuture.completedFuture(0);
        }
        @Override public java.util.concurrent.CompletionStage<Integer> control(org.yzh.protocol.t1078.T9102 command) {
            return java.util.concurrent.CompletableFuture.completedFuture(0);
        }
        @Override public void onSignalDisconnected() { }
    }
}
