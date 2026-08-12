package io.github.jtplatform.common.config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class DefaultReachableAddressResolver implements ReachableAddressResolver {
    private final Map<String, String> environment;
    private final Supplier<List<InetAddress>> addressSupplier;

    public DefaultReachableAddressResolver() {
        this(System.getenv(), DefaultReachableAddressResolver::discoverAddresses);
    }

    public DefaultReachableAddressResolver(
            Map<String, String> environment,
            Supplier<List<InetAddress>> addressSupplier) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.addressSupplier = Objects.requireNonNull(addressSupplier, "addressSupplier");
    }

    @Override
    public String resolve(ReachableAddressSettings settings) {
        ReachableAddressSettings effective = settings == null ? ReachableAddressSettings.defaults() : settings;
        return switch (effective.source()) {
            case STATIC -> requireAddress(effective.value(), "static reachable address");
            case ENV -> requireAddress(environment.get(effective.envName()),
                    "environment variable " + effective.envName());
            case AUTO -> addressSupplier.get().stream()
                    .filter(Inet4Address.class::isInstance)
                    .filter(address -> !address.isLoopbackAddress() && !address.isAnyLocalAddress())
                    .sorted(Comparator.comparing(InetAddress::isSiteLocalAddress).reversed()
                            .thenComparing(InetAddress::getHostAddress))
                    .map(InetAddress::getHostAddress)
                    .findFirst()
                    .orElse("127.0.0.1");
        };
    }

    private static List<InetAddress> discoverAddresses() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(DefaultReachableAddressResolver::isUsable)
                    .flatMap(NetworkInterface::inetAddresses)
                    .toList();
        } catch (SocketException exception) {
            return List.of();
        }
    }

    private static boolean isUsable(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp() && !networkInterface.isLoopback() && !networkInterface.isVirtual();
        } catch (SocketException exception) {
            return false;
        }
    }

    private static String requireAddress(String value, String source) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("No reachable address was provided by " + source);
        }
        String address = value.trim();
        if (address.contains("://") || address.contains("/")) {
            throw new IllegalArgumentException("Reachable address must be a host or IP, not a URL: " + address);
        }
        return address;
    }
}
