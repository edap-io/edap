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

package io.edap.crypto.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * native 实现（edap-native）的反射桥。
 *
 * <p><b>为什么用反射而不是硬依赖</b>：edap-native 在 edap-crypto 是 <b>test scope</b>
 * ——生产用户若要开启 native 加速需自行引入 edap-native。硬依赖会让「不用 native」
 * 的用户也被迫加载 JNI 库。这与 edap-auth-jwt 的 {@code HmacSha256Native} 同一套路。</p>
 *
 * <p><b>★ 探测顺序</b>：必须先用 {@code Class.forName(name)}（<b>initialize=true</b>）
 * 触发目标类的 {@code static {}}，让 edap-native 的 {@code Native.loadLibrary()} 跑完，
 * <b>然后</b>才读 {@code io.edap.jni.Native.ENABLE_NATIVE}。
 * 顺序反了会永远读到初始的 false。</p>
 *
 * <p>注意：<b>不能</b>用 {@code Class.forName(name, false, loader)}
 * —— {@code initialize=false} 不跑静态块，{@code ENABLE_NATIVE} 恒为 false。
 * 这是一个非常容易踩的坑。</p>
 */
public final class NativeBridge {

    /**
     * native 加载入口类（持有 {@code ENABLE_NATIVE} 标志）。
     */
    public static final String NATIVE_API_CLASS = "io.edap.jni.Native";

    private NativeBridge() {
    }

    /**
     * native 是否可用。
     *
     * @param className 目标 native 实现类名（其 static{} 会触发库加载）
     */
    public static boolean isNativeAvailable(String className) {
        try {
            // 顺序：先触发 static{} → Native.loadLibrary() → ENABLE_NATIVE 才可能是 true
            Class.forName(className);
            Class<?> apiCls = Class.forName(NATIVE_API_CLASS);
            Field f = apiCls.getField("ENABLE_NATIVE");
            return f.getBoolean(null);
        } catch (Throwable t) {
            // classpath 上没有 edap-native / 没有对应平台的 .o ⇒ 静默不可用
            return false;
        }
    }

    /**
     * 类是否存在（不触发初始化）。
     */
    public static boolean classExists(String className) {
        try {
            Class.forName(className, false, NativeBridge.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 构造 native 实现实例。
     *
     * @throws Throwable 类不存在或构造失败（调用方决定是否降级）
     */
    public static <T> T construct(String className, Class<?>[] sig, Object... args) throws Throwable {
        Class<?> cls = Class.forName(className);
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle ctor = lookup.findConstructor(cls, MethodType.methodType(void.class, sig));
        return (T) ctor.invoke(args);
    }

    /**
     * 查找静态方法（用于绑定 native 的 static native 方法）。
     *
     * @throws Throwable 类不存在或方法签名不匹配
     */
    public static MethodHandle findStatic(String className, String method, Class<?> ret, Class<?>... args)
            throws Throwable {
        Class<?> cls = Class.forName(className);
        return MethodHandles.lookup().findStatic(cls, method, MethodType.methodType(ret, args));
    }
}
