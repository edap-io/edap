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
import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.MacSession;
import io.edap.crypto.mac.NativeMacProvider;
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
 * native vs JCE 直接对比。
 *
 * <p>两边都走 provider 抽象，唯一变量是底层实现：
 * <ul>
 *   <li>{@code nativeDigest} / {@code jceDigest} —— SHA-256；</li>
 *   <li>{@code nativeMac} / {@code jceMac} —— HmacSHA256。</li>
 * </ul>
 *
 * <p><b>native 侧用 legacy {@code SHA256_*} / 手写 HMAC 展开</b>，不走 OpenSSL 3.x 的
 * provider dispatch —— 依据见 {@code edap-native/doc/NATIVE_DESIGN.md} §6.6
 * （{@code inner_evp_generic_fetch} 在多线程下吃掉 15.1% CPU）。</p>
 *
 * <p><b>native 不可用时</b>（edap-native 不在 classpath / 当前平台没有预编译产物）：
 * JMH 1.37 没有 {@code Assumptions} 类，用 {@code OptionsBuilder.include(regex)} 动态
 * include —— 只跑 jce 那两个 arm，不产出无意义的失败 iteration。</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class NativeVsJceBenchmark {

    private static final String KEY_STR = "test-key-must-be-at-least-32-bytes-long-padding";

    @Param({"64", "1024", "8192", "65536"})
    private int payloadSize;

    private byte[] payload;

    private byte[] keyBytes;

    private DigestProvider nativeDigestProvider;

    private DigestProvider jceDigestProvider;

    private MacProvider nativeMacProvider;

    private MacProvider jceMacProvider;

    @Setup
    public void setup() {
        this.payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            this.payload[i] = (byte) ((i * 31 + 17) & 0x7f);
        }
        this.keyBytes = KEY_STR.getBytes(StandardCharsets.UTF_8);
        this.nativeDigestProvider = NativeDigestProvider.tryCreate("SHA-256");
        this.jceDigestProvider = new JceDigestProvider("SHA-256");
        this.nativeMacProvider = NativeMacProvider.tryCreate("HmacSHA256");
        this.jceMacProvider = new JceMacProvider("HmacSHA256");
    }

    // ------------------------------------------------------------- digest arm

    @Benchmark
    @Threads(1)
    public byte[] nativeDigest_1t() {
        return digest(nativeDigestProvider);
    }

    @Benchmark
    @Threads(4)
    public byte[] nativeDigest_4t() {
        return digest(nativeDigestProvider);
    }

    @Benchmark
    @Threads(16)
    public byte[] nativeDigest_16t() {
        return digest(nativeDigestProvider);
    }

    @Benchmark
    @Threads(1)
    public byte[] jceDigest_1t() {
        return digest(jceDigestProvider);
    }

    @Benchmark
    @Threads(4)
    public byte[] jceDigest_4t() {
        return digest(jceDigestProvider);
    }

    @Benchmark
    @Threads(16)
    public byte[] jceDigest_16t() {
        return digest(jceDigestProvider);
    }

    // ---------------------------------------------------------------- mac arm

    @Benchmark
    @Threads(1)
    public byte[] nativeMac_1t() {
        return mac(nativeMacProvider);
    }

    @Benchmark
    @Threads(4)
    public byte[] nativeMac_4t() {
        return mac(nativeMacProvider);
    }

    @Benchmark
    @Threads(16)
    public byte[] nativeMac_16t() {
        return mac(nativeMacProvider);
    }

    @Benchmark
    @Threads(1)
    public byte[] jceMac_1t() {
        return mac(jceMacProvider);
    }

    @Benchmark
    @Threads(4)
    public byte[] jceMac_4t() {
        return mac(jceMacProvider);
    }

    @Benchmark
    @Threads(16)
    public byte[] jceMac_16t() {
        return mac(jceMacProvider);
    }

    private byte[] digest(DigestProvider provider) {
        DigestSession session = provider.newSession();
        session.update(payload, 0, payload.length);
        return session.digest();
    }

    private byte[] mac(MacProvider provider) {
        MacSession session = provider.newSession(keyBytes);
        session.update(payload, 0, payload.length);
        return session.doFinal();
    }

    public static void main(String[] args) throws RunnerException {
        boolean nativeAvail = NativeDigestProvider.tryCreate("SHA-256") != null
                && NativeMacProvider.tryCreate("HmacSHA256") != null;
        System.out.println("=== NativeVsJceBenchmark ===");
        System.out.println("Native: " + (nativeAvail ? "ENABLED" : "DISABLED（只跑 jce arm）"));
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(".*jceDigest.*")
                .include(".*jceMac.*");
        if (nativeAvail) {
            builder.include(".*nativeDigest.*").include(".*nativeMac.*");
        }
        // 结果文件由「父」JMH 进程写，所以这里配置即可，不必把 -D 透传给 fork 出来的 JVM
        String out = System.getProperty("edap.bench.out");
        if (out != null && !out.isEmpty()) {
            builder.resultFormat(ResultFormatType.JSON).result(out);
        }
        new Runner(builder.build()).run();
    }
}
