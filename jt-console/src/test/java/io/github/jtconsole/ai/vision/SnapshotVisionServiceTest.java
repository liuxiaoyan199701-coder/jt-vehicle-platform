package io.github.jtconsole.ai.vision;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 取图必须走网关内网路径，不能用 {@code accessAddress} 里的主机名。
 *
 * <p><b>这条规则是线上验证时才暴露的</b>：{@code accessAddress} 是给浏览器用的地址，部署方把它
 * 配成了公网 HTTPS 入口，而测试环境的证书是自签名的。控制台照着它去取图，在 TLS 握手就被 JVM
 * 拒掉——现象是「照片查得到、列表也正常，唯独画面内容永远读不出来」，而失败被记在 debug 级别，
 * 日志里什么也看不到。
 *
 * <p>用反射测一个私有静态方法是有代价的，但这条规则的正确性无法从公开行为上断言（要断言就得
 * 起一个假网关 + 假视觉服务），而它一旦被改回去，故障又只在生产环境才显形。
 */
class SnapshotVisionServiceTest {

    private static String internalPath(String address) throws Exception {
        Method method = SnapshotVisionService.class
                .getDeclaredMethod("internalPath", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, address);
    }

    @Test
    void stripsSchemeAndHostFromAnAbsoluteAddress() throws Exception {
        assertThat(internalPath("https://47.100.247.30/files/multimedia/1380000/a.jpg"))
                .isEqualTo("/files/multimedia/1380000/a.jpg");
    }

    @Test
    void keepsARelativeAddressAsIs() throws Exception {
        assertThat(internalPath("/files/multimedia/1380000/a.jpg"))
                .isEqualTo("/files/multimedia/1380000/a.jpg");
    }

    /** 网关未配置 base-url 时给的就是相对路径，但保险起见也接受不带前导斜杠的写法。 */
    @Test
    void addsALeadingSlashWhenMissing() throws Exception {
        assertThat(internalPath("files/multimedia/1380000/a.jpg"))
                .isEqualTo("/files/multimedia/1380000/a.jpg");
    }

    @Test
    void preservesQueryString() throws Exception {
        assertThat(internalPath("https://host/files/multimedia/a.jpg?token=x"))
                .isEqualTo("/files/multimedia/a.jpg?token=x");
    }

    /** 文件名里的中文与空格必须保持编码形态，否则再拼进请求就成了非法 URI。 */
    @Test
    void keepsPercentEncodingIntact() throws Exception {
        assertThat(internalPath("https://host/files/multimedia/%E7%B2%A4B%2012345.jpg"))
                .isEqualTo("/files/multimedia/%E7%B2%A4B%2012345.jpg");
    }

    @Test
    void returnsNullForBlankOrPathlessAddresses() throws Exception {
        assertThat(internalPath(null)).isNull();
        assertThat(internalPath("   ")).isNull();
        assertThat(internalPath("https://host")).isNull();
    }
}
