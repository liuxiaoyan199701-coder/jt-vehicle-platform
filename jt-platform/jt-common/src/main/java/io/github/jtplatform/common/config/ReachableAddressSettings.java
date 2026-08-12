package io.github.jtplatform.common.config;

public record ReachableAddressSettings(AddressSource source, String value, String envName) {
    public ReachableAddressSettings {
        source = source == null ? AddressSource.AUTO : source;
        envName = envName == null || envName.isBlank() ? "JT_REACHABLE_ADDRESS" : envName.trim();
        value = value == null ? "" : value.trim();
    }

    public static ReachableAddressSettings defaults() {
        return new ReachableAddressSettings(AddressSource.AUTO, "", "JT_REACHABLE_ADDRESS");
    }
}
