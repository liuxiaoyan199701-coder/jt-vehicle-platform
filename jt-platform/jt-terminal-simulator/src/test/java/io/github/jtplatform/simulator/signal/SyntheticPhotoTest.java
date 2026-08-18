package io.github.jtplatform.simulator.signal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成抓拍必须真的是一张能被解码的 JPEG。
 *
 * <p>这不是形式化的断言：整条链路后面是分包上传给平台，再由控制台交给视觉模型识别。
 * 一旦这里产出的不是合法 JPEG，故障要到「平台上收到一张打不开的图」才暴露，
 * 中间隔着编码、分包、投递、入库四层。
 */
class SyntheticPhotoTest {

    @Test
    void rendersDecodableJpegAtRequestedSize() throws IOException {
        byte[] jpeg = SyntheticPhoto.render(640, 480, "粤B12345", 1, 1, 1);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(decoded, "合成结果必须能被 ImageIO 解码");
        assertEquals(640, decoded.getWidth());
        assertEquals(480, decoded.getHeight());
    }

    @Test
    void honoursSmallResolutions() throws IOException {
        // QCIF 176×144 是分辨率表里最小的一档，字号按高度缩放，不能在这里崩掉。
        byte[] jpeg = SyntheticPhoto.render(176, 144, "粤B12345", 2, 1, 1);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(decoded);
        assertEquals(176, decoded.getWidth());
        assertEquals(144, decoded.getHeight());
    }

    /**
     * 连拍出来的几张必须彼此不同。
     *
     * <p>全都一样的话，验收时「拍 3 张」看到三张一模一样的图，无从判断是终端只拍了一张、
     * 平台去重去掉了两张，还是三张确实拍了但长得一样。
     */
    @Test
    void consecutiveShotsDiffer() throws IOException {
        Set<String> fingerprints = new HashSet<>();
        for (int index = 1; index <= 3; index++) {
            byte[] jpeg = SyntheticPhoto.render(320, 240, "粤B12345", 1, index, 3);
            fingerprints.add(java.util.Arrays.toString(jpeg));
        }
        assertEquals(3, fingerprints.size(), "同一次连拍的三张图不应完全相同");
    }

    @Test
    void producesNonTrivialPayload() throws IOException {
        byte[] jpeg = SyntheticPhoto.render(640, 480, "粤B12345", 1, 1, 1);

        // JPEG 魔数，且体积要足够大到会触发分包——分包路径本身由
        // PhotoSubpackageRoundTripTest 守着，这里只确认合成图确实会走到那条路上。
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        assertTrue(jpeg.length > 1_400, "合成图应大于单包上限，实际 " + jpeg.length);
    }
}
