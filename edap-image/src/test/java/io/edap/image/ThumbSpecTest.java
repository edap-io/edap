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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格与格式校验（不需要 libvips）。
 */
class ThumbSpecTest {

    @Test
    void defaults() {
        ThumbSpec spec = ThumbSpec.of(200);
        assertEquals(200, spec.width());
        assertNull(spec.height());
        assertEquals(ImageFormat.JPEG, spec.format());
        assertEquals(ThumbSpec.DEFAULT_QUALITY, spec.quality());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> ThumbSpec.of(0));
        assertThrows(IllegalArgumentException.class, () -> ThumbSpec.of(-1));
        assertThrows(IllegalArgumentException.class, () -> ThumbSpec.of(200, 0, ImageFormat.JPEG, 85));
        assertThrows(IllegalArgumentException.class, () -> ThumbSpec.of(200, ImageFormat.JPEG, 0));
        assertThrows(IllegalArgumentException.class, () -> ThumbSpec.of(200, ImageFormat.JPEG, 101));
        assertThrows(IllegalArgumentException.class, () -> ThumbSpec.of(200, null, null, 85));
    }

    @Test
    void magicMatching() {
        assertTrue(ImageFormat.JPEG.matches(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}));
        assertFalse(ImageFormat.JPEG.matches(new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
        assertFalse(ImageFormat.JPEG.matches(new byte[0]));
        assertFalse(ImageFormat.JPEG.matches(null));
        assertTrue(ImageFormat.WEBP.matches(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));
    }

    @Test
    void equality() {
        assertEquals(ThumbSpec.of(200), ThumbSpec.of(200));
        assertEquals(ThumbSpec.of(200).hashCode(), ThumbSpec.of(200).hashCode());
        assertFalse(ThumbSpec.of(200).equals(ThumbSpec.of(201)));
    }
}
