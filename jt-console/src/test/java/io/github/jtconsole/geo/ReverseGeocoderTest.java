package io.github.jtconsole.geo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.config.ConsoleProperties;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * 逆地理编码。
 *
 * <p>降级用例始终运行——「没配 key 时不报错、只是不带地址」是这个组件最重要的性质，不能依赖联网验证。
 * 真正打高德的用例默认跳过，设置 {@code JT_CONSOLE_AMAP_WEB_KEY} 后才跑。
 */
class ReverseGeocoderTest {

    private static ReverseGeocoder geocoder(String key) {
        ConsoleProperties properties = new ConsoleProperties();
        properties.getGeo().setAmapWebServiceKey(key);
        return new ReverseGeocoder(properties, JsonMapper.builder().build());
    }

    @Test
    void withoutAKeyItDegradesQuietlyInsteadOfFailing() {
        ReverseGeocoder geocoder = geocoder("");

        assertThat(geocoder.available()).isFalse();
        assertThat(geocoder.address(31.23, 121.47)).isEmpty();
        // 批量必须按输入长度返回，否则调用方按下标回填会错位。
        assertThat(geocoder.addresses(List.of(new double[] {31.23, 121.47}, new double[] {39.9, 116.4})))
                .hasSize(2)
                .containsOnlyNulls();
    }

    @Test
    void invalidCoordinatesNeverReachTheUpstream() {
        // 终端未定位时上报的就是 0,0；拿它去问地址只会浪费配额换回几内亚湾。
        ReverseGeocoder geocoder = geocoder("unused-because-coordinates-are-rejected-first");

        assertThat(geocoder.address(0.0, 0.0)).isEmpty();
        assertThat(geocoder.address(null, 121.47)).isEmpty();
        assertThat(geocoder.address(91.0, 121.47)).isEmpty();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JT_CONSOLE_AMAP_WEB_KEY", matches = ".+")
    void aRealCoordinateResolvesToAChineseAddress() {
        ReverseGeocoder geocoder = geocoder(System.getenv("JT_CONSOLE_AMAP_WEB_KEY"));

        assertThat(geocoder.address(31.230, 121.475)).hasValueSatisfying(
                address -> assertThat(address).contains("上海"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JT_CONSOLE_AMAP_WEB_KEY", matches = ".+")
    void batchResultsComeBackAlignedWithTheInputOrder() {
        ReverseGeocoder geocoder = geocoder(System.getenv("JT_CONSOLE_AMAP_WEB_KEY"));

        // 中间夹一个无效坐标：它必须占位为 null 而不是让后面的地址整体前移一格。
        List<String> addresses = geocoder.addresses(Arrays.asList(
                new double[] {31.230, 121.475},
                new double[] {0.0, 0.0},
                new double[] {39.909, 116.397}));

        assertThat(addresses).hasSize(3);
        assertThat(addresses.get(0)).contains("上海");
        assertThat(addresses.get(1)).isNull();
        assertThat(addresses.get(2)).contains("北京");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JT_CONSOLE_AMAP_WEB_KEY", matches = ".+")
    void repeatedLookupsOfTheSameSpotAreServedFromCache() {
        ReverseGeocoder geocoder = geocoder(System.getenv("JT_CONSOLE_AMAP_WEB_KEY"));
        String first = geocoder.address(31.230, 121.475).orElseThrow();

        // 停在同一个点的车会被反复查询；缓存键截到四位小数，轻微漂移仍命中同一条。
        long startedAt = System.nanoTime();
        String second = geocoder.address(31.23004, 121.47502).orElseThrow();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(second).isEqualTo(first);
        assertThat(elapsedMillis).isLessThan(50L);
    }
}
