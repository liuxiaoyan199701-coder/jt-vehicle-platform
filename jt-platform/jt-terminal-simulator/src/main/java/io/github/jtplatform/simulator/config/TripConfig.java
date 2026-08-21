package io.github.jtplatform.simulator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 模拟行程的配置。 */
public record TripConfig(
        boolean autoStart,
        String amapKey,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        double speedKph,
        int reportIntervalSeconds,
        boolean roundTrip,
        List<BlindspotSegment> blindspots) {

    public static final double DEFAULT_SPEED_KPH = 60.0D;
    public static final int DEFAULT_REPORT_INTERVAL_SECONDS = 10;
    public static final double MIN_SPEED_KPH = 1.0D;
    public static final double MAX_SPEED_KPH = 300.0D;
    public static final int MIN_REPORT_INTERVAL_SECONDS = 1;
    public static final int MAX_REPORT_INTERVAL_SECONDS = 600;

    /** 兼容既有源码调用方；默认无自动盲区。 */
    public TripConfig(boolean autoStart, String amapKey, Double originLat, Double originLng,
            Double destinationLat, Double destinationLng, double speedKph,
            int reportIntervalSeconds, boolean roundTrip) {
        this(autoStart, amapKey, originLat, originLng, destinationLat, destinationLng,
                speedKph, reportIntervalSeconds, roundTrip, List.of());
    }

    public TripConfig {
        amapKey = amapKey == null ? "" : amapKey.trim();
        originLat = validLatitude(originLat);
        originLng = validLongitude(originLng);
        destinationLat = validLatitude(destinationLat);
        destinationLng = validLongitude(destinationLng);
        if (originLat == null || originLng == null) {
            originLat = null;
            originLng = null;
        }
        if (destinationLat == null || destinationLng == null) {
            destinationLat = null;
            destinationLng = null;
        }
        speedKph = inRange(speedKph, MIN_SPEED_KPH, MAX_SPEED_KPH, DEFAULT_SPEED_KPH);
        reportIntervalSeconds = (int) inRange(reportIntervalSeconds,
                MIN_REPORT_INTERVAL_SECONDS, MAX_REPORT_INTERVAL_SECONDS,
                DEFAULT_REPORT_INTERVAL_SECONDS);
        blindspots = blindspots == null ? List.of() : List.copyOf(blindspots);
        for (int i = 1; i < blindspots.size(); i++) {
            if (blindspots.get(i - 1).endPercent() > blindspots.get(i).startPercent()) {
                throw new IllegalArgumentException("blindspot segments must not overlap");
            }
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static TripConfig fromJson(
            @JsonProperty("autoStart") Boolean autoStart,
            @JsonProperty("amapKey") String amapKey,
            @JsonProperty("originLat") Double originLat,
            @JsonProperty("originLng") Double originLng,
            @JsonProperty("destinationLat") Double destinationLat,
            @JsonProperty("destinationLng") Double destinationLng,
            @JsonProperty("speedKph") Double speedKph,
            @JsonProperty("reportIntervalSeconds") Integer reportIntervalSeconds,
            @JsonProperty("roundTrip") Boolean roundTrip,
            @JsonProperty("blindspots") List<BlindspotSegment> blindspots) {
        TripConfig defaults = defaults();
        return new TripConfig(
                autoStart == null ? defaults.autoStart() : autoStart,
                amapKey, originLat, originLng, destinationLat, destinationLng,
                speedKph == null ? defaults.speedKph() : speedKph,
                reportIntervalSeconds == null ? defaults.reportIntervalSeconds() : reportIntervalSeconds,
                roundTrip == null ? defaults.roundTrip() : roundTrip,
                blindspots == null ? defaults.blindspots() : blindspots);
    }

    public static TripConfig defaults() {
        return new TripConfig(false, "", null, null, null, null,
                DEFAULT_SPEED_KPH, DEFAULT_REPORT_INTERVAL_SECONDS, true, List.of());
    }

    public boolean hasAmapKey() { return !amapKey.isEmpty(); }
    public boolean hasOrigin() { return originLat != null && originLng != null; }
    public boolean hasDestination() { return destinationLat != null && destinationLng != null; }

    private static Double validLatitude(Double value) {
        return value != null && Double.isFinite(value) && value >= -90 && value <= 90 ? value : null;
    }
    private static Double validLongitude(Double value) {
        return value != null && Double.isFinite(value) && value >= -180 && value <= 180 ? value : null;
    }
    private static double inRange(double value, double min, double max, double fallback) {
        return Double.isFinite(value) && value >= min && value <= max ? value : fallback;
    }
}
