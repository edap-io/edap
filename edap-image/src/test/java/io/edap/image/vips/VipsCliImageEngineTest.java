/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.image.vips;

import io.edap.image.ImageFormat;
import io.edap.image.ImageProcessingException;
import io.edap.image.ThumbSpec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * libvips 命令行缩略图引擎的行为验证。
 *
 * <p>本机没装 libvips 命令行时整体跳过（libvips 是运行期依赖，测试无法在所有开发机上跑）。</p>
 */
class VipsCliImageEngineTest {

    private static final int SRC_WIDTH = 400;
    private static final int SRC_HEIGHT = 300;

    private final VipsCliImageEngine engine = new VipsCliImageEngine();

    @BeforeAll
    static void requireVips() {
        VipsCliImageEngine probe = new VipsCliImageEngine();
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.available(),
                "本机没有可用的 vips 命令行，跳过。原因: " + probe.unavailableReason());
    }

    @Test
    void thumbnailKeepsAspectRatio() throws Exception {
        byte[] out = engine.thumbnail(TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT), ThumbSpec.of(200));
        assertTrue(ImageFormat.JPEG.matches(out), "产物不是 JPEG");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(out));
        assertEquals(200, image.getWidth());
        assertEquals(150, image.getHeight());
    }

    @Test
    void doesNotUpscale() throws Exception {
        byte[] out = engine.thumbnail(TestImages.jpeg(100, 80), ThumbSpec.of(200));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(out));
        assertEquals(100, image.getWidth());
        assertEquals(80, image.getHeight());
    }

    @Test
    void fitsIntoHeightBox() throws Exception {
        byte[] out = engine.thumbnail(TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT),
                ThumbSpec.of(200, 100, ImageFormat.JPEG, 85));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(out));
        assertTrue(image.getWidth() <= 200, "宽超出: " + image.getWidth());
        assertTrue(image.getHeight() <= 100, "高超出: " + image.getHeight());
        assertEquals(100, image.getHeight());
    }

    @Test
    void webpOutput() {
        byte[] out = engine.thumbnail(TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT),
                ThumbSpec.of(200, ImageFormat.WEBP, 80));
        assertTrue(ImageFormat.WEBP.matches(out), "产物不是 WebP");
        assertEquals('W', out[8]);
        assertEquals('E', out[9]);
        assertEquals('B', out[10]);
        assertEquals('P', out[11]);
    }

    /**
     * 质量参数必须真的生效 —— 命令行下质量只挂在编码步骤上，
     * 如果两步流水线写错（例如漏掉 --Q），这个测试会先发现。
     */
    @Test
    void qualityAffectsOutput() {
        byte[] low = engine.thumbnail(TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT),
                ThumbSpec.of(200, ImageFormat.JPEG, 10));
        byte[] high = engine.thumbnail(TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT),
                ThumbSpec.of(200, ImageFormat.JPEG, 95));
        assertTrue(low.length < high.length,
                "质量参数没生效: q10=" + low.length + " q95=" + high.length);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void exifOrientationLandscape(int orientation) throws Exception {
        byte[] src = TestImages.withExifOrientation(TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT), orientation);
        byte[] out = engine.thumbnail(src, ThumbSpec.of(200));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(out));
        assertEquals(200, image.getWidth(), "orientation=" + orientation);
        assertEquals(150, image.getHeight(), "orientation=" + orientation);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6, 7, 8})
    void exifOrientationPortrait(int orientation) throws Exception {
        byte[] src = TestImages.withExifOrientation(TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT), orientation);
        byte[] out = engine.thumbnail(src, ThumbSpec.of(200));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(out));
        assertEquals(150, image.getWidth(), "orientation=" + orientation);
        assertEquals(200, image.getHeight(), "orientation=" + orientation);
    }

    @Test
    void acceptsPngInput() throws Exception {
        byte[] out = engine.thumbnail(TestImages.png(SRC_WIDTH, SRC_HEIGHT), ThumbSpec.of(200));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(out));
        assertEquals(200, image.getWidth());
        assertEquals(150, image.getHeight());
    }

    @Test
    void rejectsGarbage() {
        byte[] junk = "this is definitely not an image".getBytes();
        ImageProcessingException e = assertThrows(ImageProcessingException.class,
                () -> engine.thumbnail(junk, ThumbSpec.of(200)));
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
    }

    /**
     * 非白名单格式必须在交给 libvips 之前就被拒掉 —— SVG 是按扩展名匹配的 loader，
     * PDF 会拖起 poppler，两者都是不可信输入的攻击面。
     */
    @Test
    void rejectsUnsupportedInputFormats() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(ImageProcessingException.class, () -> engine.thumbnail(svg, ThumbSpec.of(200)));

        byte[] pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
        assertThrows(ImageProcessingException.class, () -> engine.thumbnail(pdf, ThumbSpec.of(200)));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(ImageProcessingException.class, () -> engine.thumbnail(new byte[0], ThumbSpec.of(200)));
    }

    @Test
    void rejectsNullSource() {
        assertThrows(ImageProcessingException.class, () -> engine.thumbnail(null, ThumbSpec.of(200)));
    }

    @Test
    void rejectsOversizedInput() {
        VipsCliImageEngine small = new VipsCliImageEngine(1024, VipsCliImageEngine.DEFAULT_MAX_PIXELS,
                VipsCliImageEngine.DEFAULT_TIMEOUT_SECONDS);
        byte[] src = TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT);
        ImageProcessingException e = assertThrows(ImageProcessingException.class,
                () -> small.thumbnail(src, ThumbSpec.of(200)));
        assertTrue(e.getMessage().contains("过大"), e.getMessage());
    }

    /**
     * 解码炸弹闸门：PNG 没有 shrink-on-load，必须整幅解码，尺寸超限要被挡住。
     */
    @Test
    void rejectsTooManyPixels() {
        VipsCliImageEngine small = new VipsCliImageEngine(
                VipsCliImageEngine.DEFAULT_MAX_INPUT_BYTES, 1_000_000L,
                VipsCliImageEngine.DEFAULT_TIMEOUT_SECONDS);
        byte[] src = TestImages.png(1200, 900);
        assertThrows(ImageProcessingException.class, () -> small.thumbnail(src, ThumbSpec.of(200)));
    }

    @Test
    void reportsUnavailableWhenBinaryMissing() {
        String previous = System.getProperty(VipsCliImageEngine.BINARY_PROPERTY);
        try {
            System.setProperty(VipsCliImageEngine.BINARY_PROPERTY, "/nonexistent/vips");
            VipsCliImageEngine engine = new VipsCliImageEngine();
            assertFalse(engine.available());
            assertTrue(engine.unavailableReason() != null);
            assertThrows(ImageProcessingException.class,
                    () -> engine.thumbnail(TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT), ThumbSpec.of(200)));
        } finally {
            if (previous == null) {
                System.clearProperty(VipsCliImageEngine.BINARY_PROPERTY);
            } else {
                System.setProperty(VipsCliImageEngine.BINARY_PROPERTY, previous);
            }
        }
    }

    @Test
    void concurrentThumbnails() throws Exception {
        int threads = 8;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            byte[] src = TestImages.jpeg(SRC_WIDTH, SRC_HEIGHT);
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < threads * perThread; i++) {
                futures.add(pool.submit(() -> engine.thumbnail(src, ThumbSpec.of(200))));
            }
            for (Future<byte[]> future : futures) {
                byte[] out = future.get(120, TimeUnit.SECONDS);
                assertTrue(ImageFormat.JPEG.matches(out));
                assertNotEquals(0, out.length);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
