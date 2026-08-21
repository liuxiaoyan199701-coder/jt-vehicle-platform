package io.github.jtconsole.geo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.ai.tool.GeoTools;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PlaceSearchClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesPoiCandidatesInLatLngOrderAndLimitsToFive() {
        StringBuilder body = new StringBuilder("{\"status\":\"1\",\"pois\":[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"name\":\"地点").append(i)
                    .append("\",\"address\":\"上海市地址").append(i)
                    .append("\",\"location\":\"121.4737,31.2304\"}");
        }
        body.append("]}");

        List<PlaceSearchClient.PlaceCandidate> candidates =
                PlaceSearchClient.parsePlaces(MAPPER, body.toString());

        assertThat(candidates).hasSize(5);
        assertThat(candidates.getFirst().name()).isEqualTo("地点0");
        assertThat(candidates.getFirst().address()).isEqualTo("上海市地址0");
        // 高德 location 是 lng,lat；对外和围栏链路统一为 lat,lng。
        assertThat(candidates.getFirst().lat()).isEqualTo(31.2304);
        assertThat(candidates.getFirst().lng()).isEqualTo(121.4737);
    }

    @Test
    void parsesGeocodeFallbackCandidates() {
        String body = "{\"status\":\"1\",\"geocodes\":["
                + "{\"formatted_address\":\"上海市黄浦区人民广场\","
                + "\"location\":\"121.4737,31.2304\"}]}";

        List<PlaceSearchClient.PlaceCandidate> candidates =
                PlaceSearchClient.parseGeocodes(MAPPER, body);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.name()).isEqualTo("上海市黄浦区人民广场");
            assertThat(candidate.address()).isEqualTo("上海市黄浦区人民广场");
            assertThat(candidate.lat()).isEqualTo(31.2304);
            assertThat(candidate.lng()).isEqualTo(121.4737);
        });
    }

    @Test
    void malformedOrEmptyUpstreamResponsesBecomeEmptyCandidates() {
        assertThat(PlaceSearchClient.parsePlaces(MAPPER, "")).isEmpty();
        assertThat(PlaceSearchClient.parsePlaces(MAPPER, "{\"status\":\"0\"}")).isEmpty();
        assertThat(PlaceSearchClient.parsePlaces(MAPPER,
                "{\"status\":\"1\",\"pois\":[{\"name\":\"无坐标\"}]}")).isEmpty();
        assertThat(PlaceSearchClient.parseGeocodes(MAPPER, "not-json")).isEmpty();
    }

    @Test
    void missingKeyMakesSearchClientUnavailableAndReturnsEmpty() {
        ConsoleProperties properties = new ConsoleProperties();
        PlaceSearchClient client = new PlaceSearchClient(properties, MAPPER);

        assertThat(client.available()).isFalse();
        assertThat(client.search("龙华东二村", "深圳")).isEmpty();
    }

    @Test
    void coordinateContractUsesGcj02WithoutAnotherWgs84Conversion() {
        // 围栏保存与高德结果都使用 GCJ-02；这个已知 WGS-84 点若转换会得到明显不同的数值。
        double[] gcj = CoordTransform.wgs84ToGcj02(31.2304, 121.4737);
        PlaceSearchClient.PlaceCandidate candidate =
                PlaceSearchClient.parsePlaces(MAPPER,
                        "{\"status\":\"1\",\"pois\":[{\"name\":\"人民广场\","
                                + "\"location\":\"121.4737,31.2304\"}]}").getFirst();

        assertThat(candidate.lat()).isEqualTo(31.2304);
        assertThat(candidate.lng()).isEqualTo(121.4737);
        assertThat(candidate.lat()).isNotEqualTo(gcj[0]);
        assertThat(candidate.lng()).isNotEqualTo(gcj[1]);
    }

    @Test
    void keyConditionDoesNotMatchMissingOrBlankKey() {
        assertThat(conditionMatches(null)).isFalse();
        assertThat(conditionMatches("   ")).isFalse();
        assertThat(conditionMatches("test-key")).isTrue();
    }

    private static boolean conditionMatches(String key) {
        // GeoTools 的条件是注册边界：没有有效 key 时，AI 不应看到 search_place。
        org.springframework.mock.env.MockEnvironment environment =
                new org.springframework.mock.env.MockEnvironment();
        if (key != null) {
            environment.setProperty("jt.console.geo.amap-web-service-key", key);
        }
        org.springframework.context.annotation.ConditionContext context =
                new TestConditionContext(environment);
        return GeoTools.KeyConfiguredCondition.class.cast(new GeoTools.KeyConfiguredCondition())
                .getMatchOutcome(context, null).isMatch();
    }

    private record TestConditionContext(
            org.springframework.core.env.Environment environment)
            implements org.springframework.context.annotation.ConditionContext {
        @Override
        public org.springframework.core.env.Environment getEnvironment() {
            return environment;
        }

        @Override
        public org.springframework.beans.factory.config.ConfigurableListableBeanFactory getBeanFactory() {
            return null;
        }

        @Override
        public org.springframework.beans.factory.support.BeanDefinitionRegistry getRegistry() {
            return null;
        }

        @Override
        public org.springframework.core.io.ResourceLoader getResourceLoader() {
            return null;
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }
}
