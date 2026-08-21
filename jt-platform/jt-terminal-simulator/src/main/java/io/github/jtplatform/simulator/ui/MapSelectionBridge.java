package io.github.jtplatform.simulator.ui;

import java.util.Objects;
import java.util.function.Consumer;

/** Java 与地图页面之间的窄桥：页面只回传数值，业务层负责校验和保存。 */
public final class MapSelectionBridge {
    private final Consumer<MapSelection> confirmed;
    private MapPoint origin;
    private MapPoint destination;

    public MapSelectionBridge(MapPoint initialOrigin, MapPoint initialDestination,
            Consumer<MapSelection> confirmed) {
        this.origin = initialOrigin;
        this.destination = initialDestination;
        this.confirmed = Objects.requireNonNull(confirmed, "confirmed");
    }

    public void setOrigin(double latitude, double longitude) {
        origin = new MapPoint(latitude, longitude);
    }

    public void setDestination(double latitude, double longitude) {
        destination = new MapPoint(latitude, longitude);
    }

    public boolean ready() {
        return origin != null && destination != null;
    }

    public MapSelection selection() {
        if (!ready()) {
            throw new IllegalStateException("请先选择起点和终点");
        }
        return new MapSelection(origin, destination);
    }

    public void confirm() {
        confirmed.accept(selection());
    }

    public String initialStateJson() {
        return "{\"origin\":%s,\"destination\":%s}".formatted(pointJson(origin), pointJson(destination));
    }

    private static String pointJson(MapPoint point) {
        return point == null ? "null"
                : "{\"lat\":%s,\"lng\":%s}".formatted(point.latitudeText(), point.longitudeText());
    }
}
