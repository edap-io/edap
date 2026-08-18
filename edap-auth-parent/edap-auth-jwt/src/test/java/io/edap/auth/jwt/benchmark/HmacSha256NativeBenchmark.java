package io.edap.auth.jwt.benchmark;

import io.edap.auth.jwt.algorithm.HmacSha256;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Java vs Native HMAC-SHA256 性能对比。
 *
 * <p><b>对比对象</b>：
 * <ul>
 *   <li><b>javaMac</b> — {@link javax.crypto.Mac} HmacSHA256 + ThreadLocal<Mac>（生产代码同款模式）</li>
 *   <li><b>nativeHmac</b> — {@link HmacSha256Native} OpenSSL JNI（反射调用）</li>
 * </ul>
 *
 * <p><b>维度</b>：
 * <ul>
 *   <li>payload 大小：100 / 500 / 2000 字节（典型 JWT body 范围）</li>
 *   <li>线程数：1 / 4 / 16（暴露 ThreadLocal<Mac> 与 native stateless 在并发下的差异）</li>
 * </ul>
 *
 * <p><b>运行</b>：mvn test-compile 后用 {@code -DincludeScope=test} 拉 test-scope 依赖
 * （edap-native 在 edap-auth-jwt 里是 test scope，runtime 不会带）：
 * <pre>{@code
 * java -cp edap-auth-jwt/target/test-classes:edap-auth-jwt/target/classes:\
 * $(mvn -pl edap-auth-jwt dependency:build-classpath -q \
 *    -DincludeScope=test -Dmdep.outputFile=/dev/stdout) \
 *   io.edap.auth.jwt.benchmark.HmacSha256NativeBenchmark
 *
 * # 强制只跑 Java 对照组：
 * java -Dedap.jwt.hmac.native=false -cp ...
 * }</pre></p>
 *
 * <p><b>默认行为</b>：native 默认启用（edap-native 在 classpath + 当前平台 .o 加载成功）
 * 自动走 native 路径；只有显式 {@code -Dedap.jwt.hmac.native=false} 时才强制 Java。
 *
 * <p><b>native 不可用时</b>：JMH 1.37 没有 {@code Assumptions} 类（1.38 也不存在；
 * 该类从未进入 JMH），改用 {@link OptionsBuilder#include(String) OptionsBuilder.include}
 * 按 regex 动态 include benchmark：native 可用 → include {@code .*javaMac.*} +
 * {@code .*nativeHmac.*}；native 不可用 → 仅 include {@code .*javaMac.*}。
 * 这样既不会编译失败，也不会产出无意义的失败 iteration。</p>
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
public class HmacSha256NativeBenchmark {

    private static final String KEY_STR = "test-key-must-be-at-least-32-bytes-long-padding";

    @Param({"100", "500", "2000"})
    private int payloadSize;

    private byte[] keyBytes;
    private byte[] payload;

    /** 生产代码同款：每线程持有独立 Mac 实例，避免 reset/update 内部 buffer 竞态 */
    private ThreadLocal<Mac> javaMacHolder;

    /** Native 路径：instance 内部 key 不可变，sign() 无共享状态 */
    private HmacSha256.HmacSha256Native nativeHmac;

    private boolean nativeEnabled;

    @Setup
    public void setup() {
        this.keyBytes = KEY_STR.getBytes(StandardCharsets.UTF_8);
        // 构造一个非全 0 的 payload，避免某些实现的 zero-skip 优化
        this.payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            this.payload[i] = (byte) ((i * 31 + 17) & 0x7f);
        }

        this.javaMacHolder = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
                return mac;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        this.nativeEnabled = HmacSha256.HmacSha256Native.isAvailable();
        if (nativeEnabled) {
            this.nativeHmac = new HmacSha256.HmacSha256Native(KEY_STR);
        }
    }

    @TearDown
    public void tearDown() {
        // Mac 实例是 ThreadLocal，线程结束由 JVM 清理；无需手动 remove
    }

    /** Java 自带 HMAC-SHA256（ThreadLocal<Mac> 模式） */
    @Benchmark
    @Threads(1)
    public byte[] javaMac_1t() {
        Mac mac = javaMacHolder.get();
        mac.reset();
        mac.update(payload, 0, payload.length);
        return mac.doFinal();
    }

    @Benchmark
    @Threads(4)
    public byte[] javaMac_4t() {
        Mac mac = javaMacHolder.get();
        mac.reset();
        mac.update(payload, 0, payload.length);
        return mac.doFinal();
    }

    @Benchmark
    @Threads(16)
    public byte[] javaMac_16t() {
        Mac mac = javaMacHolder.get();
        mac.reset();
        mac.update(payload, 0, payload.length);
        return mac.doFinal();
    }

    /** OpenSSL JNI（via reflection）— main 方法按 nativeEnabled 动态 include/exclude */
    @Benchmark
    @Threads(1)
    public byte[] nativeHmac_1t() {
        return nativeHmac.sign(payload, 0, payload.length);
    }

    @Benchmark
    @Threads(4)
    public byte[] nativeHmac_4t() {
        return nativeHmac.sign(payload, 0, payload.length);
    }

    @Benchmark
    @Threads(16)
    public byte[] nativeHmac_16t() {
        return nativeHmac.sign(payload, 0, payload.length);
    }

    public static void main(String[] args) throws RunnerException {
        boolean nativeAvail = HmacSha256.HmacSha256Native.isAvailable();
        System.out.println("=== HmacSha256 Native vs Java Benchmark ===");
        System.out.println("Payload sizes: 100 / 500 / 2000 bytes");
        System.out.println("Threads:       1 / 4 / 16");
        System.out.println("Native:        " + (nativeAvail ? "ENABLED (default)" : "DISABLED (will skip native bench)"));
        System.out.println("Disable flag:  -Dedap.jwt.hmac.native=false to force Java");
        if (!nativeAvail) {
            System.out.println();
            System.out.println("!! NOTE: native not available. Common cause:");
            System.out.println("   - edap-native missing from classpath (run mvn dependency:build-classpath with -DincludeScope=test)");
            System.out.println("   - current OS/arch has no prebuilt .o (only macos_aarch64 currently)");
        }
        System.out.println();

        // JMH 1.37 没有 Assumptions；用 OptionsBuilder.include() 过滤
        // native 不可用时只 include javaMac_*；native 可用时 include 所有
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(".*javaMac.*");
        if (nativeAvail) {
            builder.include(".*nativeHmac.*");
        }
        new Runner(builder.build()).run();
    }
}
