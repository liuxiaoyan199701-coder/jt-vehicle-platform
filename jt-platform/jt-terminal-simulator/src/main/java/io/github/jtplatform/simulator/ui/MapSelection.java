package io.github.jtplatform.simulator.ui;

import java.util.Objects;

/** 地图选点结果；两点均为 GCJ-02。 */
public record MapSelection(MapPoint origin, MapPoint destination) {
    public MapSelection {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
    }
}
