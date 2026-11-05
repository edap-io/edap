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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/**
 * <b>修前基线</b>：裸 JCE —— 每次调用都 {@code getInstance} 新建实例。
 *
 * <p>这是 edap 迁移到 {@code CryptoProvider} <b>之前</b>的代码形态：
 * {@code CryptUtil.md5/sha1}、{@code SigV4Verifier} 每次调用都新建
 * {@link MessageDigest} / {@link Mac}。</p>
 *
 * <p><b>本类永久保留</b> —— 用来复现「修前 vs 修后」的对比，不随消费方迁移而删改。</p>
 *
 * <p><b>维度</b>：payload 64 / 1024 / 8192 / 65536 字节；线程 1 / 4 / 16。</p>
 *
 * <p>三个 arm 分别对应三个真实调用点：</p>
 * <ul>
 *   <li>{@code md5} —— {@code CryptUtil.md5(byte[])} 的形态（{@code getInstance} 每次新建）；</li>
 *   <li>{@code sha1} —— {@code CryptUtil.sha1(byte[])} 的形态；</li>
 *   <li>{@code mac} —— {@code SigV4Verifier} 的形态（{@code getInstance} + {@code init}）；</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class CryptoBaselineBenchmark {

    private static final String KEY_STR = "test-key-must-be-at-least-32-bytes-long-padding";

    @Param({"64", "1024", "8192", "65536"})
    private int payloadSize;

    private byte[] payload;

    private byte[] keyBytes;

    @Setup
    public void setup() {
        this.payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            // 非全 0，避免某些实现有 zero-skip 快路径
            this.payload[i] = (byte) ((i * 31 + 17) & 0x7f);
        }
        this.keyBytes = KEY_STR.getBytes(StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------------- md5 arm

    @Benchmark
    @Threads(1)
    public byte[] md5_1t() throws Exception {
        return MessageDigest.getInstance("md5").digest(payload);
    }

    @Benchmark
    @Threads(4)
    public byte[] md5_4t() throws Exception {
        return MessageDigest.getInstance("md5").digest(payload);
    }

    @Benchmark
    @Threads(16)
    public byte[] md5_16t() throws Exception {
        return MessageDigest.getInstance("md5").digest(payload);
    }

    // -------------------------------------------------------------- sha1 arm

    @Benchmark
    @Threads(1)
    public byte[] sha1_1t() throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(payload);
    }

    @Benchmark
    @Threads(4)
    public byte[] sha1_4t() throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(payload);
    }

    @Benchmark
    @Threads(16)
    public byte[] sha1_16t() throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(payload);
    }

    // --------------------------------------------------------------- mac arm

    @Benchmark
    @Threads(1)
    public byte[] mac_1t() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
        return mac.doFinal(payload);
    }

    @Benchmark
    @Threads(4)
    public byte[] mac_4t() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
        return mac.doFinal(payload);
    }

    @Benchmark
    @Threads(16)
    public byte[] mac_16t() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
        return mac.doFinal(payload);
    }

    public static void main(String[] args) throws RunnerException {
        System.out.println("=== CryptoBaselineBenchmark（修前：裸 JCE，每次 getInstance） ===");
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(".*" + CryptoBaselineBenchmark.class.getSimpleName() + ".*");
        // 结果文件由「父」JMH 进程写，所以这里配置即可，不必把 -D 透传给 fork 出来的 JVM
        String out = System.getProperty("edap.bench.out");
        if (out != null && !out.isEmpty()) {
            builder.resultFormat(ResultFormatType.JSON).result(out);
        }
        new Runner(builder.build()).run();
    }
}
