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

package io.edap.crypto.test;

import io.edap.crypto.internal.NativeBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NativeBridge} —— native 可用性探测的行为契约。
 *
 * <p><b>核心契约</b>：探测必须是「先 {@code Class.forName(name)}（initialize=true）
 * 再读 {@code ENABLE_NATIVE}」，顺序反了会永远读到 false。</p>
 */
public class NativeBridgeTest {

    @Test
    @DisplayName("类不存在 ⇒ 返回 false 而不是抛异常")
    public void testMissingClassReturnsFalse() {
        assertFalse(NativeBridge.isNativeAvailable("io.edap.jni.NoSuchClass"));
        assertFalse(NativeBridge.isNativeAvailable("no.such.package.Foo"));
    }

    @Test
    @DisplayName("classExists 不触发初始化")
    public void testClassExists() {
        assertTrue(NativeBridge.classExists("io.edap.crypto.internal.NativeBridge"));
        assertFalse(NativeBridge.classExists("io.edap.crypto.internal.NoSuchClass"));
    }

    @Test
    @DisplayName("findStatic 找不到方法 ⇒ 抛 Throwable")
    public void testFindStaticMissingMethod() {
        assertThrows(Throwable.class, () -> NativeBridge.findStatic(
                "io.edap.crypto.internal.NativeBridge", "noSuchMethod", void.class));
    }

    @Test
    @DisplayName("findStatic 能绑定到真实存在的 static 方法")
    public void testFindStaticExistingMethod() throws Throwable {
        assertDoesNotThrow(() -> NativeBridge.findStatic(
                "io.edap.crypto.internal.NativeBridge", "classExists", boolean.class, String.class));
    }

    @Test
    @DisplayName("construct 类不存在 ⇒ 抛 Throwable")
    public void testConstructMissingClass() {
        assertThrows(Throwable.class, () -> NativeBridge.construct(
                "io.edap.jni.NoSuchClass", new Class<?>[0]));
    }
}
