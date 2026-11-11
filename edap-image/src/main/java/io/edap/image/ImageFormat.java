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

/**
 * 缩略图输出格式。
 *
 * <p>每个格式都带一个<b>magic 前缀</b>，用于校验编码产物的真实格式。这个校验不是多余的：
 * 用 ImageMagick 处理时实测遇到过「找不到对应编码器就把 PNG 悄悄写进目标文件」的情况，
 * 文件存在、命令返回 0，但内容完全不是要的东西。libvips 在这点上更诚实（直接返回错误），
 * 但产物校验仍然保留 —— 校验成本只有几个字节比较，收益是杜绝「拿到垃圾却当成功」。</p>
 */
public enum ImageFormat {

    /** JPEG，magic 前缀 {@code FF D8 FF}。 */
    JPEG(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),

    /** WebP，magic 前缀 {@code RIFF}（容器是 RIFF，格式标记在偏移 8）。 */
    WEBP(new byte[]{'R', 'I', 'F', 'F'});

    private final byte[] magic;

    ImageFormat(byte[] magic) {
        this.magic = magic;
    }

    /**
     * magic 前缀。
     *
     * @return 魔数字节数组，调用方不得修改
     */
    public byte[] magic() {
        return magic.clone();
    }

    /**
     * 判断数据是否以本格式的 magic 开头。
     *
     * @param data 待校验的字节数组
     * @return 是否匹配
     */
    public boolean matches(byte[] data) {
        if (data == null || data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
