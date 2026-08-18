package io.github.jtplatform.simulator.trip;

import java.util.List;

/**
 * 「给我两点之间的一条驾车折线」——{@link RoutePlanner} 对外部地图服务的全部要求。
 *
 * <p>接口只有一个方法，且只有一个生产实现（{@link AmapDirectionsClient}）。存在的理由不是预留
 * 多实现，而是给降级链的测试一个不需要起 HTTP 服务器的接缝：降级链有五条分支，其中四条都以
 * 「这次调用失败了」为前提，用假服务器逐条构造既慢又绕。
 */
@FunctionalInterface
public interface DirectionsService {

    /**
     * @return 沿真实道路的折线，**加密坐标系**
     * @throws AmapException 调用失败。失败是常态，调用方必须降级而不是中断
     */
    List<GeoPoint> drivingRoute(GeoPoint origin, GeoPoint destination, String key)
            throws AmapException;
}
