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

/**
 * edap-crypto 的告警出口。
 *
 * <p><b>为什么 edap-crypto 不用 edap-log-api 的 logger</b>：</p>
 *
 * <p>日志子系统的启动路径是：{@code LoggerManager.init()} → {@code ServiceLoader}
 * 找到 {@code io.edap.log.spi.LoggerServiceProvider} → provider 构造 → appender 初始化
 * → 生成编码器类名 → <b>{@code CryptUtil.md5(...)}</b>。</p>
 *
 * <p>而 {@code CryptUtil} 在 C-8 迁移后要走 edap-crypto 的 {@code CryptoRegistry}。
 * 如果 edap-crypto 反过来依赖 edap-log-api 并在类初始化时调用
 * {@code LoggerManager.getLogger(...)}，就会形成一条跨 4 个模块的<b>类初始化环</b>：</p>
 *
 * <pre>
 * LoggerManager.init() --SPI--> EdapLogServiceProvider --AppenderManager-->
 *   EncoderGenerator --CryptUtil.md5--> CryptoRegistry.&lt;clinit&gt; --getLogger--&gt; LoggerManager ⟲
 * </pre>
 *
 * <p>同线程重入 {@code CryptoRegistry.&lt;clinit&gt;} 时，JVM 直接返回，
 * 于是它的静态 map 还是 {@code null} ⇒ {@code NullPointerException}
 * ⇒ SPI provider 实例化失败 ⇒ 整个应用<b>退化成 NopLogger（一行日志都没有）</b>。</p>
 *
 * <p>所以 edap-crypto <b>不依赖 edap-log-api</b>：降级提示默认直接写 stderr
 * （与 {@code LoggerManager} 自己找不到 provider 时用 {@code Util.printError} 的做法一致），
 * 宿主应用可以用 {@link #setSink(Sink)} 把它接到自己的 logger 上。</p>
 */
public final class CryptoDiagnostics {

    /**
     * 告警出口 —— 宿主可以换成自己的实现，把提示送到真正的日志系统。
     */
    @FunctionalInterface
    public interface Sink {
        /**
         * 输出一条告警。
         */
        void warn(String message);
    }

    /**
     * 默认出口：写 stderr。
     */
    private static final Sink STDERR_SINK = message ->
            System.err.println("[edap-crypto] WARN " + message);

    private static volatile Sink sink = STDERR_SINK;

    private CryptoDiagnostics() {
    }

    /**
     * 替换告警出口 —— 宿主应用用来把提示接进自己的日志系统。
     *
     * <p>传 {@code null} 恢复默认（stderr）。</p>
     */
    public static void setSink(Sink newSink) {
        sink = (newSink == null) ? STDERR_SINK : newSink;
    }

    /**
     * 当前告警出口。
     */
    public static Sink getSink() {
        return sink;
    }

    /**
     * 输出一条告警（不去重 —— 去重由调用方负责）。
     */
    public static void warn(String message) {
        sink.warn(message);
    }

    /**
     * 恢复默认出口 —— <b>仅供测试</b>。
     */
    public static void resetSinkForTest() {
        sink = STDERR_SINK;
    }
}
