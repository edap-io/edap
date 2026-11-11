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

package io.edap.image;

import java.util.Objects;

/**
 * 缩略图规格（不可变值对象）。
 *
 * <p>尺寸语义：{@code width} 是必须给的目标宽度，{@code height} 可空 —— 给定时输出会等比
 * 缩放到能放进 {@code width × height} 的框内；不给出时按原图宽高比推算高度。
 * <b>只缩小不放大</b>：源图本来就比目标小的时候保持原尺寸。</p>
 */
public final class ThumbSpec {

    /** 默认 JPEG 质量。 */
    public static final int DEFAULT_QUALITY = 85;

    private final int width;
    private final Integer height;
    private final ImageFormat format;
    private final int quality;

    private ThumbSpec(int width, Integer height, ImageFormat format, int quality) {
        if (width <= 0) {
            throw new IllegalArgumentException("width 必须大于 0: " + width);
        }
        if (height != null && height <= 0) {
            throw new IllegalArgumentException("height 必须大于 0: " + height);
        }
        if (format == null) {
            throw new IllegalArgumentException("format 不能为 null");
        }
        if (quality < 1 || quality > 100) {
            throw new IllegalArgumentException("quality 必须在 1~100 之间: " + quality);
        }
        this.width = width;
        this.height = height;
        this.format = format;
        this.quality = quality;
    }

    /**
     * 只指定宽度的 JPEG 缩略图（质量 {@value #DEFAULT_QUALITY}）。
     *
     * @param width 目标宽度，必须大于 0
     * @return 规格
     */
    public static ThumbSpec of(int width) {
        return new ThumbSpec(width, null, ImageFormat.JPEG, DEFAULT_QUALITY);
    }

    /**
     * 指定宽度、格式与质量。
     *
     * @param width   目标宽度，必须大于 0
     * @param format  输出格式
     * @param quality 编码质量，1~100
     * @return 规格
     */
    public static ThumbSpec of(int width, ImageFormat format, int quality) {
        return new ThumbSpec(width, null, format, quality);
    }

    /**
     * 指定宽度、高度、格式与质量。
     *
     * @param width   目标宽度，必须大于 0
     * @param height  目标高度，可空；给出时输出等比缩放到该框内
     * @param format  输出格式
     * @param quality 编码质量，1~100
     * @return 规格
     */
    public static ThumbSpec of(int width, Integer height, ImageFormat format, int quality) {
        return new ThumbSpec(width, height, format, quality);
    }

    public int width() {
        return width;
    }

    public Integer height() {
        return height;
    }

    public ImageFormat format() {
        return format;
    }

    public int quality() {
        return quality;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ThumbSpec)) {
            return false;
        }
        ThumbSpec other = (ThumbSpec) o;
        return width == other.width
                && Objects.equals(height, other.height)
                && format == other.format
                && quality == other.quality;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, format, quality);
    }

    @Override
    public String toString() {
        return "ThumbSpec{width=" + width
                + ", height=" + height
                + ", format=" + format
                + ", quality=" + quality
                + '}';
    }
}
