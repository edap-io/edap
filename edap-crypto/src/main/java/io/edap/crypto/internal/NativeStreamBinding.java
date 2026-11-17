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

/**
 * edap-native 流式入口的反射绑定。
 *
 * <p>把 {@code io.edap.jni.crypto.NativeStream} 的三个入口
 * （{@code update / finish / free}）绑成 {@link MethodHandle}，
 * 摘要与 MAC 两个 provider 共用同一份绑定。</p>
 *
 * <p><b>为什么用反射而不是硬依赖</b>：edap-native 在 edap-crypto 是 <b>test scope</b> ——
 * 生产用户若要开启 native 加速需自行引入 edap-native。理由与 {@link NativeBridge} 相同。</p>
 *
 * <p><b>★ 符号缺失必须炸出来，不能静默降级</b>：绑定 native 方法<b>不会</b>因为
 * 「.o 里没有这个符号」而失败 —— Java 侧的 native 方法声明一直都在，
 * 只有真正调用时才会抛 {@link UnsatisfiedLinkError}。那时排查的人看到的是
 * 一句「UnsatisfiedLinkError」，而不是「预编译产物过期了，重新构建」，
 * 而且报错位置离原因十万八千里。
 * ⇒ 本类把 {@link UnsatisfiedLinkError} 转成
 * {@link IllegalStateException} +「产物过期，请重建」的文案。</p>
 */
public final class NativeStreamBinding {

    /**
     * edap-native 里的流式实现类。
     */
    public static final String NATIVE_CLASS = "io.edap.jni.crypto.NativeStream";

    private static volatile NativeStreamBinding instance;

    private final MethodHandle updateHandle;

    private final MethodHandle finishHandle;

    private final MethodHandle freeHandle;

    private NativeStreamBinding(MethodHandle updateHandle, MethodHandle finishHandle,
            MethodHandle freeHandle) {
        this.updateHandle = updateHandle;
        this.finishHandle = finishHandle;
        this.freeHandle = freeHandle;
    }

    /**
     * 取（并缓存）绑定。
     *
     * <p>缓存的意义：绑定本身是一次反射查找 + 句柄创建，热路径上不该重复付。</p>
     *
     * @throws Throwable 类或方法不存在（edap-native 版本太老 / 不在 classpath 上）
     */
    public static NativeStreamBinding get() throws Throwable {
        NativeStreamBinding local = instance;
        if (local == null) {
            synchronized (NativeStreamBinding.class) {
                local = instance;
                if (local == null) {
                    local = bind();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static NativeStreamBinding bind() throws Throwable {
        return new NativeStreamBinding(
                NativeBridge.findStatic(NATIVE_CLASS, "update", long.class,
                        long.class, int.class, byte[].class, int.class, byte[].class, int.class, int.class),
                NativeBridge.findStatic(NATIVE_CLASS, "finish", byte[].class, long.class),
                NativeBridge.findStatic(NATIVE_CLASS, "free", void.class, long.class));
    }

    /**
     * 追加一段数据；<b>{@code ctx == 0} 时顺便把上下文建出来</b>。
     *
     * <p>{@code alg} / {@code key} / {@code keyLen} 只在 {@code ctx == 0} 时有意义
     * —— 此时 native 侧用它们建上下文（HMAC 还要用 key 展开 ipad/opad）。</p>
     *
     * @param ctx    上下文句柄；0 表示还没有上下文，本次调用会建一个
     * @param alg    算法编号（{@code io.edap.jni.crypto.NativeStream} 的常量）
     * @param key    HMAC 的 key；纯摘要传 {@code null}
     * @param keyLen key 长度（字节）；纯摘要传 0
     * @param data   数据
     * @param offset 起始偏移
     * @param len    长度
     * @return 上下文句柄；失败返回 0
     */
    public long update(long ctx, int alg, byte[] key, int keyLen,
            byte[] data, int offset, int len) {
        try {
            return (long) updateHandle.invokeExact(ctx, alg, key, keyLen, data, offset, len);
        } catch (Throwable t) {
            throw failure("update", t);
        }
    }

    /**
     * 算出结果并<b>释放上下文</b>（句柄随即失效，不能再 {@link #free}）。
     */
    public byte[] finish(long ctx) {
        try {
            return (byte[]) finishHandle.invokeExact(ctx);
        } catch (Throwable t) {
            throw failure("finish", t);
        }
    }

    /**
     * 放弃上下文（不算结果直接释放）。
     */
    public void free(long ctx) {
        try {
            freeHandle.invokeExact(ctx);
        } catch (Throwable t) {
            throw failure("free", t);
        }
    }

    /**
     * 「预编译产物过期」的报错文案（诊断/测试用）。
     *
     * @param symbol 缺失的 native 方法名（报错里要点名）
     */
    public static String staleArtifactMessage(String symbol) {
        return "edap-native 预编译产物过期：缺少 JNI 符号 " + NATIVE_CLASS + "." + symbol
                + "（当前 .o 与 Java 侧不是同一版本）。"
                + "请在目标机上重新构建：edap-native/scripts/build-native.sh --commit";
    }

    /**
     * 把 native 调用失败转成可读的异常。
     *
     * <p><b>★ 符号缺失单独一类</b>：{@link UnsatisfiedLinkError} 只可能是
     * 「预编译产物与 Java 侧不是同一版本」⇒ 配置/构建错误 ⇒ 必须在报错里点名，
     * 而不是让人对着 UnsatisfiedLinkError 猜。</p>
     */
    private static RuntimeException failure(String symbol, Throwable t) {
        if (t instanceof UnsatisfiedLinkError) {
            return new IllegalStateException(staleArtifactMessage(symbol), t);
        }
        return new IllegalStateException(
                "native 流式调用失败：" + NATIVE_CLASS + "." + symbol + " —— " + t, t);
    }
}
