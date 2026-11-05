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
import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.MacSession;
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
 * <b>修后</b>：走 {@code CryptoProvider} 抽象（JCE 实现，实例按线程复用）。
 *
 * <p>与 {@link CryptoBaselineBenchmark} 构成 before/after 对：唯一的变量是
 * 「每次 {@code getInstance}」⇒「provider 持有 ThreadLocal 实例池，按 session 独占借用」。
 * 两边都用 <b>JCE 实现</b>，这样对比只隔离「实例复用」这一个变量；
 * native 相对 JCE 的收益在 {@link NativeVsJceBenchmark} 里单独测。</p>
 *
 * <p><b>为什么 session 是独占的</b>：{@code MessageDigest.reset()} / {@code Mac.init()}
 * 会清掉已喂的数据 ⇒ 同一线程里两个「活」session 共用一个实例时，后一个会把前一个
 * 的数据清掉，算出来的结果是错的且不报错。所以 provider 的每个 session 独占一个实例，
 * 算完（{@code digest()} / {@code doFinal()}）再还回池子。</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class CryptoProviderBenchmark {

    private static final String KEY_STR = "test-key-must-be-at-least-32-bytes-long-padding";

    @Param({"64", "1024", "8192", "65536"})
    private int payloadSize;

    private byte[] payload;

    private byte[] keyBytes;

    private DigestProvider md5Provider;

    private DigestProvider sha1Provider;

    private MacProvider macProvider;

    @Setup
    public void setup() {
        this.payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            this.payload[i] = (byte) ((i * 31 + 17) & 0x7f);
        }
        this.keyBytes = KEY_STR.getBytes(StandardCharsets.UTF_8);
        this.md5Provider = new JceDigestProvider("md5");
        this.sha1Provider = new JceDigestProvider("SHA-1");
        this.macProvider = new JceMacProvider("HmacSHA256");
    }

    // --------------------------------------------------------------- md5 arm

    @Benchmark
    @Threads(1)
    public byte[] md5_1t() {
        return digest(md5Provider);
    }

    @Benchmark
    @Threads(4)
    public byte[] md5_4t() {
        return digest(md5Provider);
    }

    @Benchmark
    @Threads(16)
    public byte[] md5_16t() {
        return digest(md5Provider);
    }

    // -------------------------------------------------------------- sha1 arm

    @Benchmark
    @Threads(1)
    public byte[] sha1_1t() {
        return digest(sha1Provider);
    }

    @Benchmark
    @Threads(4)
    public byte[] sha1_4t() {
        return digest(sha1Provider);
    }

    @Benchmark
    @Threads(16)
    public byte[] sha1_16t() {
        return digest(sha1Provider);
    }

    // --------------------------------------------------------------- mac arm

    @Benchmark
    @Threads(1)
    public byte[] mac_1t() {
        return mac(macProvider);
    }

    @Benchmark
    @Threads(4)
    public byte[] mac_4t() {
        return mac(macProvider);
    }

    @Benchmark
    @Threads(16)
    public byte[] mac_16t() {
        return mac(macProvider);
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
        System.out.println("=== CryptoProviderBenchmark（修后：走 provider，实例按线程复用） ===");
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(".*" + CryptoProviderBenchmark.class.getSimpleName() + ".*");
        // 结果文件由「父」JMH 进程写，所以这里配置即可，不必把 -D 透传给 fork 出来的 JVM
        String out = System.getProperty("edap.bench.out");
        if (out != null && !out.isEmpty()) {
            builder.resultFormat(ResultFormatType.JSON).result(out);
        }
        new Runner(builder.build()).run();
    }
}
