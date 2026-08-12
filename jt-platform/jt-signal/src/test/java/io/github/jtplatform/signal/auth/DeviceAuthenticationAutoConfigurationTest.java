package io.github.jtplatform.signal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.signal.config.DeviceAuthProperties;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.yzh.web.model.entity.DeviceDO;

class DeviceAuthenticationAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeviceAuthenticationAutoConfiguration.class));

    @Test
    void defaultModeAllowsDevicesWithoutCallingRemoteClient() {
        AtomicInteger remoteCalls = new AtomicInteger();
        runner.withBean(RemoteDeviceInformationClient.class, () -> terminalId -> {
                    remoteCalls.incrementAndGet();
                    throw new AssertionError("default mode must not call a remote source");
                })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(DeviceAuthMode.ALLOW_ALL,
                            context.getBean(DeviceAuthProperties.class).getMode());
                    assertInstanceOf(AllowAllDeviceInformationSource.class,
                            context.getBean(DeviceInformationSource.class));
                    DeviceAuthenticationDecision decision = context.getBean(DeviceAuthenticationService.class)
                            .authenticate(device("terminal-1"));
                    assertTrue(decision.allowed());
                    assertEquals("terminal-1", decision.device().getDeviceId());
                    assertEquals(0, remoteCalls.get());
                });
    }

    @Test
    void localListAllowsOnlyConfiguredDevicesWithoutCallingRemoteClient() {
        AtomicInteger remoteCalls = new AtomicInteger();
        runner.withBean(RemoteDeviceInformationClient.class, () -> terminalId -> {
                    remoteCalls.incrementAndGet();
                    return Optional.of(new DeviceInformation(terminalId, terminalId, null, null));
                })
                .withPropertyValues(
                        "jt.auth.device.mode=local-list",
                        "jt.auth.device.local-list[0]=terminal-1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    DeviceAuthenticationService service = context.getBean(DeviceAuthenticationService.class);
                    assertTrue(service.authenticate(device("terminal-1")).allowed());
                    assertFalse(service.authenticate(device("terminal-2")).allowed());
                    assertEquals(0, remoteCalls.get());
                });
    }

    @Test
    void remoteModeCachesFoundAndMissingInformationAndMakesTheDecisionLocally() {
        AtomicInteger remoteCalls = new AtomicInteger();
        RemoteDeviceInformationClient client = terminalId -> {
            remoteCalls.incrementAndGet();
            return switch (terminalId) {
                case "known" -> Optional.of(new DeviceInformation("known", "known", "mobile-1", "plate-1"));
                case "mismatch" -> Optional.of(new DeviceInformation("another-terminal", "mismatch", null, null));
                default -> Optional.empty();
            };
        };

        runner.withBean(RemoteDeviceInformationClient.class, () -> client)
                .withPropertyValues(
                        "jt.auth.device.mode=remote-api",
                        "jt.auth.device.remote.unavailable-policy=deny",
                        "jt.auth.device.remote.cache-ttl=1m",
                        "jt.auth.device.remote.cache-maximum-size=20")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    DeviceAuthenticationService service = context.getBean(DeviceAuthenticationService.class);

                    DeviceAuthenticationDecision first = service.authenticate(device("known"));
                    DeviceAuthenticationDecision second = service.authenticate(device("known"));
                    assertTrue(first.allowed());
                    assertTrue(second.allowed());
                    assertEquals("mobile-1", first.device().getMobileNo());
                    assertEquals(1, remoteCalls.get());

                    assertFalse(service.authenticate(device("missing")).allowed());
                    assertFalse(service.authenticate(device("missing")).allowed());
                    assertEquals(2, remoteCalls.get());

                    assertFalse(service.authenticate(device("mismatch")).allowed());
                    assertEquals(3, remoteCalls.get());
                });
    }

    @Test
    void remoteModeRefusesToStartWithoutAnExplicitUnavailablePolicy() {
        runner.withBean(RemoteDeviceInformationClient.class, () -> terminalId -> Optional.empty())
                .withPropertyValues("jt.auth.device.mode=remote-api")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(allMessages(failure).contains(
                            "jt.auth.device.remote.unavailable-policy must be explicitly set"));
                });
    }

    @Test
    void remoteFailurePassesThroughTheCacheAndUsesTheConfiguredPolicy() {
        runner.withBean(RemoteDeviceInformationClient.class, () -> terminalId -> {
                    throw new DeviceInformationUnavailableException("offline");
                })
                .withPropertyValues(
                        "jt.auth.device.mode=remote-api",
                        "jt.auth.device.remote.unavailable-policy=allow")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(DeviceAuthenticationService.class)
                            .authenticate(device("terminal-1"))
                            .allowed());
                });
    }

    private static DeviceDO device(String terminalId) {
        return new DeviceDO().setDeviceId(terminalId).setMobileNo("mobile-" + terminalId);
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }
}
