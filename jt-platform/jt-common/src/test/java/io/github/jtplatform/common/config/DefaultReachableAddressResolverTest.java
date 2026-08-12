package io.github.jtplatform.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultReachableAddressResolverTest {
    @Test
    void resolvesStaticEnvironmentAndAutomaticSources() throws Exception {
        DefaultReachableAddressResolver resolver = new DefaultReachableAddressResolver(
                Map.of("PUBLISHED_HOST", "media.example.test"),
                () -> addresses("127.0.0.1", "192.0.2.40"));

        assertEquals("edge.example.test", resolver.resolve(
                new ReachableAddressSettings(AddressSource.STATIC, "edge.example.test", null)));
        assertEquals("media.example.test", resolver.resolve(
                new ReachableAddressSettings(AddressSource.ENV, null, "PUBLISHED_HOST")));
        assertEquals("192.0.2.40", resolver.resolve(ReachableAddressSettings.defaults()));
    }

    @Test
    void rejectsMissingConfiguredValues() {
        DefaultReachableAddressResolver resolver = new DefaultReachableAddressResolver(Map.of(), List::of);
        assertThrows(IllegalStateException.class, () -> resolver.resolve(
                new ReachableAddressSettings(AddressSource.ENV, null, "MISSING_HOST")));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(
                new ReachableAddressSettings(AddressSource.STATIC, null, null)));
    }

    private static List<InetAddress> addresses(String... values) {
        return java.util.Arrays.stream(values).map(value -> {
            try {
                return InetAddress.getByName(value);
            } catch (Exception exception) {
                throw new IllegalArgumentException(exception);
            }
        }).toList();
    }
}
