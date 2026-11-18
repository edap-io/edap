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

package io.edap.crypto.benchmark;

import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.DigestSession;
import io.edap.crypto.digest.JceDigestProvider;
import io.edap.crypto.digest.NativeDigestProvider;
import io.edap.jni.crypto.NativeStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * <b>诊断基准</b>：把 native 摘要路径上的「MethodHandle 派发」这一层摘掉。
 *
 * <p><b>要查的现象</b>：{@code nativeDigest_16t@64B} 在 macOS 上呈<b>整 fork 双峰</b>
 * —— 同一个 fork 的 5 次迭代全在一个档（要么 ~1050 ns，要么 ~1410 ns），
 * 换 fork 才换档；而同一批次里的 {@code jceDigest_16t@64B} 不抖。
 * 更大的载荷（@1024 / @8192 / @64KB）全稳 ⇒ 只在小载荷上可见。</p>
 *
 * <p><b>两个候选机制</b>（都是「native 独有 + 负载敏感」）：</p>
 * <ol>
 *   <li><b>MethodHandle 派发</b>：{@code NativeStreamBinding} 用
 *       {@code MethodHandle.invokeExact} 调 native，三个句柄是
 *       <b>private final 实例字段</b> ⇒ C2 无法常量折叠 ⇒ 每次调用走 LambdaForm 路径。
 *       JIT 编译线程在负载下被饿 ⇒ LambdaForm 编译与否变成 per-fork 的二元状态
 *       ⇒ 整 fork 一个档。</li>
 *   <li><b>{@code GetPrimitiveArrayCritical} 的 GCLocker</b>：native 侧用 critical section
 *       直接喂数据（不拷贝）；JCE 侧没有这层。数组在年轻代 ⇒ critical section 要拷一份，
 *       在老年代 ⇒ 直接给指针。是否已晋升是 per-fork 的时机问题。</li>
 * </ol>
 *
 * <p><b>本类怎么区分</b>：{@code direct_*} 臂<b>直接静态调用</b>
 * {@code NativeStream.update / finish}（没有 MethodHandle、没有 {@code NativeDigestProvider}），
 * 与 {@code binding_*} 臂（走 {@code NativeDigestProvider} 的 MethodHandle 路径）对照。
 * 若「direct 稳、binding 抖」⇒ 机制 1；若两者一起抖 ⇒ 机制 2。</p>
 *
 * <p><b>为什么单独一个类</b>：本类在类初始化期就引用 {@code io.edap.jni.crypto.NativeStream}
 * —— 它的 {@code static {}} 会跑 {@code Native.loadLibrary()}。在不支持的平台上
 * 这会让<b>整个类</b>加载失败 ⇒ 不能把它塞进 {@link NativeVsJceBenchmark}。</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class NativeBindingVsDirectBenchmark {

    @Param({"64", "1024"})
    private int payloadSize;

    private byte[] payload;

    private DigestProvider bindingProvider;

    private DigestProvider jceProvider;

    @Setup
    public void setup() {
        this.payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            this.payload[i] = (byte) ((i * 31 + 17) & 0x7f);
        }
        this.bindingProvider = NativeDigestProvider.tryCreate("SHA-256");
        this.jceProvider = new JceDigestProvider("SHA-256");
        // 直接引一次 NativeStream，触发其 static{}（loadLibrary）
        NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, new byte[0], 0, 0);
    }

    /** 直调 native（无 MethodHandle）。 */
    @Benchmark
    @Threads(16)
    public byte[] direct_16t() {
        long ctx = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, payload, 0, payload.length);
        return NativeStream.finish(ctx);
    }

    /** 8 线程 —— 等于 M1 Max 的物理 P 核数（8 P + 2 E）。 */
    @Benchmark
    @Threads(8)
    public byte[] direct_8t() {
        long ctx = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, payload, 0, payload.length);
        return NativeStream.finish(ctx);
    }

    /** 12 线程 —— 越过 P 核数但没到 16。 */
    @Benchmark
    @Threads(12)
    public byte[] direct_12t() {
        long ctx = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, payload, 0, payload.length);
        return NativeStream.finish(ctx);
    }

    /** 8 线程的 MethodHandle 路径。 */
    @Benchmark
    @Threads(8)
    public byte[] binding_8t() {
        return digest(bindingProvider);
    }

    /** 8 线程的 JCE 路径。 */
    @Benchmark
    @Threads(8)
    public byte[] jce_8t() {
        return digest(jceProvider);
    }

    /** 走 {@code NativeDigestProvider}（MethodHandle 派发）。 */
    @Benchmark
    @Threads(16)
    public byte[] binding_16t() {
        return digest(bindingProvider);
    }

    /** JCE 对照。 */
    @Benchmark
    @Threads(16)
    public byte[] jce_16t() {
        return digest(jceProvider);
    }

    /** 直调 native，单线程（量一下 MethodHandle 派发的绝对成本）。 */
    @Benchmark
    @Threads(1)
    public byte[] direct_1t() {
        long ctx = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, payload, 0, payload.length);
        return NativeStream.finish(ctx);
    }

    /** MethodHandle 派发，单线程。 */
    @Benchmark
    @Threads(1)
    public byte[] binding_1t() {
        return digest(bindingProvider);
    }

    private byte[] digest(DigestProvider provider) {
        DigestSession session = provider.newSession();
        session.update(payload, 0, payload.length);
        return session.digest();
    }

    public static void main(String[] args) throws RunnerException {
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(".*direct.*")
                .include(".*binding.*")
                .include(".*jce.*");
        String include = System.getProperty("edap.bench.include");
        if (include != null && !include.isEmpty()) {
            ChainedOptionsBuilder fresh = new OptionsBuilder();
            for (String re : include.split(",")) {
                fresh.include(re.trim());
            }
            builder = fresh;
        }
        String param = System.getProperty("edap.bench.param");
        if (param != null && !param.isEmpty()) {
            for (String kv : param.split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    builder.param(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }
        String jvmArgs = System.getProperty("edap.bench.jvmArgs");
        if (jvmArgs != null && !jvmArgs.isEmpty()) {
            for (String a : jvmArgs.split(",")) {
                if (!a.trim().isEmpty()) {
                    builder.jvmArgsAppend(a.trim());
                }
            }
        }
        String out = System.getProperty("edap.bench.out");
        if (out != null && !out.isEmpty()) {
            builder.resultFormat(ResultFormatType.JSON).result(out);
        }
        new Runner(builder.build()).run();
    }
}
