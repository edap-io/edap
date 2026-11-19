/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.rpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InterceptorLoader} 的 SPI 装载测试。
 */
public class InterceptorLoaderTest {

    @Test
    public void loadsFromServiceFile() {
        List<SpiTestInterceptor> loaded = InterceptorLoader.load(SpiTestInterceptor.class);

        assertEquals(1, loaded.size());
        assertEquals("spi", loaded.get(0).name());
    }

    @Test
    public void failsFastWhenServiceClassMissing() {
        // SPI 文件里写的是不存在的类 ⇒ 装不上 ⇒ fail-fast
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> InterceptorLoader.load(BrokenSpiInterceptor.class));

        assertTrue(e.getMessage().contains(BrokenSpiInterceptor.class.getName()));
    }

    @Test
    public void noServiceFileYieldsEmptyList() {
        List<NoSpiInterceptor> loaded = InterceptorLoader.load(NoSpiInterceptor.class);

        assertTrue(loaded.isEmpty());
    }

    @Test
    public void nullTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> InterceptorLoader.load(null));
    }
}
