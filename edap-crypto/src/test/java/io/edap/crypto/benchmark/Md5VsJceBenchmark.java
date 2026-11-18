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

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * MD5：native（JNI / OpenSSL）vs JCE 的直接对比。
 *
 * <p>与 {@link NativeVsJceBenchmark} 分开成一个类，原因是那两个汇总脚本
 * （{@code ab-summary.py} / {@code native-vs-jce-summary.py}）都按**方法名里有没有
 * {@code Digest} / {@code Mac}** 来分类原语 —— 把 MD5 的 arm 塞进同一个类，
 * MD5 与 SHA-256 会在 (kind, arm, 线程数, payload) 这个 key 上撞车，
 * 两组数会被静默合并（这正是 {@code summarize-bench.py} 踩过的坑）。</p>
 *
 * <p>两边都走 provider 抽象，唯一变量是底层实现：</p>
 * <ul>
 *   <li>{@code nativeMd5} —— edap-native 的流式摘要（算法编号 2），legacy {@code MD5_*}；</li>
 *   <li>{@code jceMd5} —— {@code MessageDigest.getInstance("MD5")}。</li>
 * </ul>
 *
 * <p><b>★ JCE 侧的 provider 归属必须一起看</b>：本机（macOS）MD5 落在
 * {@code SUN} provider（纯 Java 实现），而 Solaris 上可能落在 {@code OracleUcrypto}
 * （原生实现）⇒ <b>同一份代码在两台机器上的「JCE 侧成本」不是一回事</b>，
 * 比值不能直接跨平台外推。setup 里会把实际 provider 打出来。</p>
 *
 * <p><b>★ 防「测的是 JCE vs JCE」</b>：native 不可用时
 * {@code NativeDigestProvider.tryCreate} 会返回 null（静默降级），
 * 那时两臂跑的是同一个实现、比值恒为 1.00 却看不出来 ⇒
 * setup 里做两件事：① 打印 native 是否可用；② <b>逐字节比对两臂的摘要</b>，
 * 不一致直接抛异常把这一轮作废。</p>
 *
 * <p><b>诊断开关</b>（同 {@link NativeVsJceBenchmark}）：
 * {@code -Dedap.bench.include=<regex,...>} / {@code -Dedap.bench.param=<k=v,...>} /
 * {@code -Dedap.bench.jvmArgs=<arg,...>} / {@code -Dedap.bench.out=<file.json>}</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class Md5VsJceBenchmark {

    /**
     * 算法名 —— 两边都用它（JCE 侧直接进 {@code MessageDigest}，native 侧映射到编号 2）。
     */
    private static final String ALG = "MD5";

    @Param({"64", "1024", "8192", "65536"})
    private int payloadSize;

    private byte[] payload;

    private DigestProvider nativeDigestProvider;

    private DigestProvider jceDigestProvider;

    /**
     * native 是否可用（不可用时只跑 jce arm —— 否则两臂是同一个实现，比值毫无意义）。
     */
    private boolean nativeAvailable;

    @Setup
    public void setup() {
        this.payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            this.payload[i] = (byte) ((i * 31 + 17) & 0x7f);
        }
        this.nativeDigestProvider = NativeDigestProvider.tryCreate(ALG);
        this.jceDigestProvider = new JceDigestProvider(ALG);
        this.nativeAvailable = this.nativeDigestProvider != null;

        System.out.println("=== Md5VsJceBenchmark ===");
        System.out.println("payloadSize = " + payloadSize);
        System.out.println("JCE provider = " + jceProviderName());
        System.out.println("Native: " + (nativeAvailable ? "ENABLED" : "DISABLED（只跑 jce arm）"));

        // ★ 逐字节比对：两臂必须算出同一个 MD5，否则这一轮的数据没有意义
        byte[] jce = digest(jceDigestProvider);
        if (nativeAvailable) {
            byte[] nat = digest(nativeDigestProvider);
            if (!Arrays.equals(jce, nat)) {
                throw new IllegalStateException("native 与 JCE 的 MD5 不一致 —— 这一轮作废：jce="
                        + hex(jce) + " native=" + hex(nat));
            }
        }
        System.out.println("md5(" + payloadSize + "B) = " + hex(jce));
    }

    /**
     * JCE 侧实际落到哪个 provider —— 不同平台结论完全不同，必须随数据一起报。
     */
    private static String jceProviderName() {
        try {
            MessageDigest md = MessageDigest.getInstance(ALG);
            return md.getProvider().getName() + " (" + md.getAlgorithm() + ")";
        } catch (Exception e) {
            return "unknown: " + e;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- native arm

    @Benchmark
    @Threads(1)
    public byte[] nativeMd5_1t() {
        return digest(nativeDigestProvider);
    }

    @Benchmark
    @Threads(4)
    public byte[] nativeMd5_4t() {
        return digest(nativeDigestProvider);
    }

    @Benchmark
    @Threads(16)
    public byte[] nativeMd5_16t() {
        return digest(nativeDigestProvider);
    }

    // ---------------------------------------------------------------- jce arm

    @Benchmark
    @Threads(1)
    public byte[] jceMd5_1t() {
        return digest(jceDigestProvider);
    }

    @Benchmark
    @Threads(4)
    public byte[] jceMd5_4t() {
        return digest(jceDigestProvider);
    }

    @Benchmark
    @Threads(16)
    public byte[] jceMd5_16t() {
        return digest(jceDigestProvider);
    }

    private byte[] digest(DigestProvider provider) {
        DigestSession session = provider.newSession();
        session.update(payload, 0, payload.length);
        return session.digest();
    }

    public static void main(String[] args) throws RunnerException {
        boolean nativeAvail = NativeDigestProvider.tryCreate(ALG) != null;
        ChainedOptionsBuilder builder = new OptionsBuilder().include(".*jceMd5.*");
        if (nativeAvail) {
            builder.include(".*nativeMd5.*");
        }

        String include = System.getProperty("edap.bench.include");
        if (include != null && !include.isEmpty()) {
            builder = new OptionsBuilder();
            for (String re : include.split(",")) {
                builder.include(re.trim());
            }
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
