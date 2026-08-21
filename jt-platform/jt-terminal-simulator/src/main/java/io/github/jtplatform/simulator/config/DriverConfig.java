package io.github.jtplatform.simulator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 驾驶员身份识别（0x0702）的表单配置。 */
public record DriverConfig(
        String name,
        String idCard,
        String licenseNo,
        String institution,
        String licenseValidPeriod) {

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static DriverConfig fromJson(
            @JsonProperty("name") String name,
            @JsonProperty("idCard") String idCard,
            @JsonProperty("licenseNo") String licenseNo,
            @JsonProperty("institution") String institution,
            @JsonProperty("licenseValidPeriod") String licenseValidPeriod) {
        return new DriverConfig(name, idCard, licenseNo, institution, licenseValidPeriod);
    }

    public DriverConfig {
        name = text(name);
        idCard = text(idCard);
        licenseNo = text(licenseNo);
        institution = text(institution);
        licenseValidPeriod = text(licenseValidPeriod);
    }

    public static DriverConfig defaults() {
        return new DriverConfig("", "", "", "", "");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
