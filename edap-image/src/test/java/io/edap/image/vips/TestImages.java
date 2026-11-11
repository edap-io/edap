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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

/**
 * 测试用图片构造。
 *
 * <p>不依赖任何外部资源：图片用 JDK 自带的 ImageIO 生成，EXIF 方向用手写的 APP1 段注入
 * （JDK 的 ImageIO 不会写 EXIF，而 EXIF 方向正是缩略图最容易出错的地方，必须覆盖）。</p>
 */
final class TestImages {

    private TestImages() {
    }

    /**
     * 生成一张渐变 JPEG。
     *
     * @param width  宽
     * @param height 高
     * @return JPEG 字节
     */
    static byte[] jpeg(int width, int height) {
        return encode(width, height, "jpg");
    }

    /**
     * 生成一张渐变图片。
     *
     * @param width  宽
     * @param height 高
     * @param format ImageIO 格式名
     * @return 图片字节
     */
    private static byte[] encode(int width, int height, String format) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = x * 255 / Math.max(1, width - 1);
                int g = y * 255 / Math.max(1, height - 1);
                image.setRGB(x, y, (r << 16) | (g << 8) | 0x40);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new IllegalStateException("生成测试图片失败", e);
        }
        return out.toByteArray();
    }

    /**
     * 生成一张渐变 PNG。
     *
     * <p>PNG 没有 JPEG 的 shrink-on-load，libvips 必须整幅解码 —— 测「解码炸弹闸门」
     * 这类尺寸限制时只有 PNG 才测得出真实行为。</p>
     *
     * @param width  宽
     * @param height 高
     * @return PNG 字节
     */
    static byte[] png(int width, int height) {
        return encode(width, height, "png");
    }

    /**
     * 给 JPEG 注入 EXIF Orientation 标签。
     *
     * @param jpeg        原始 JPEG
     * @param orientation 方向值 1~8
     * @return 带 EXIF 的 JPEG
     */
    static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = tiff(orientation);
        byte[] payload = new byte[6 + tiff.length];
        byte[] exif = "Exif".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(exif, 0, payload, 0, 4);
        payload[4] = 0;
        payload[5] = 0;
        System.arraycopy(tiff, 0, payload, 6, tiff.length);

        ByteBuffer segment = ByteBuffer.allocate(4 + payload.length).order(ByteOrder.BIG_ENDIAN);
        segment.put((byte) 0xFF).put((byte) 0xE1);
        segment.putShort((short) (2 + payload.length));
        segment.put(payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(jpeg, 0, 2);
            out.write(segment.array());
            out.write(jpeg, 2, jpeg.length - 2);
        } catch (IOException e) {
            throw new IllegalStateException("注入 EXIF 失败", e);
        }
        return out.toByteArray();
    }

    /**
     * 构造只含一个 Orientation 条目的最小 TIFF 块。
     *
     * @param orientation 方向值
     * @return TIFF 字节
     */
    private static byte[] tiff(int orientation) {
        ByteBuffer buf = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("II".getBytes(StandardCharsets.US_ASCII));
        buf.putShort((short) 0x002A);
        buf.putInt(8);
        buf.putShort((short) 1);
        buf.putShort((short) 0x0112);
        buf.putShort((short) 3);
        buf.putInt(1);
        buf.putShort((short) orientation);
        buf.putShort((short) 0);
        buf.putInt(0);
        return buf.array();
    }
}
