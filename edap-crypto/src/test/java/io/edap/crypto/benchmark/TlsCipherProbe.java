/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.edap.crypto.benchmark;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>TLS 对称加密路径的吞吐探针</b> —— 回答「在目标机上，TLS 的对称加密
 * （{@code AES/GCM}、{@code ChaCha20-Poly1305}）到底要吃几个核」。
 *
 * <p><b>它回答的问题</b>：TLS 的开销分两段 ——
 * <b>握手</b>（非对称、每次连接一次）与<b>每记录的对称加密</b>（大块、每条记录一次）。
 * 后者是「吞吐型」开销：它决定在 1 Gbps / 10 Gbps 的链路上，加密要占掉几个核。
 * 这个数不出来，就没法判断「值不值得为 TLS 引入 JNI 或 nginx 前置」。</p>
 *
 * <p><b>★ 为什么要把 JDK 侧和 OpenSSL 侧放在一起测</b>：
 * 「用 JNI 把 TLS 挪到 OpenSSL」这条路的收益，等于「OpenSSL 的对称加密比 JDK 快多少」
 * （JNI 那一次调用相对于 16KB 的加密量可以忽略）。
 * 而 <b>OpenSSL 侧不需要写任何 JNI</b>：目标机上有 {@code openssl speed -evp <alg>}，
 * 它量的是同一件事（EVP 上下文复用、逐块加密）⇒
 * <b>一个不写 JNI 的对比就能决定「JNI 化 TLS 值不值得」</b>。</p>
 *
 * <p><b>★ 三种形态（这是本探针的关键口径）</b>：</p>
 * <ul>
 *   <li>{@code tls} —— <b>TLS 的真实形态</b>：每条记录 {@code init + updateAAD + doFinal}。
 *       源码依据见 {@link #SHAPE_TLS} 的注释（JDK 17 {@code SSLCipher} 的
 *       {@code T12/T13GcmWriteCipher}）—— <b>AEAD 每条记录都重新 init</b>。</li>
 *   <li>{@code oneshot} —— 每条消息 {@code getInstance + init + doFinal}
 *       （应用层「一密文一消息」，edap-crypto 的 {@code CipherSession} 就是这个形态）。</li>
 *   <li>{@code ctxreuse} —— init 一次、逐条 {@code update}。
 *       <b>不是 TLS 的形态</b>，只作「上下文复用能省多少」的上界参考。</li>
 * </ul>
 * 三个形态的差 = 「每条记录的固定成本 + AAD + GHASH 收尾」，直接决定
 * 「能不能用 JNI 换掉固定成本」这条路值不值得 —— 与 {@code JvmVsJniPerfTest}
 * 回答 SHA-256/HMAC 时用的是同一把尺子。
 *
 * <p><b>测量口径</b>（memory {@code feedback_benchmark_isolated_jvm}）：
 * <b>一个测点一个 JVM</b>（父进程起子 JVM）、批内取 min、跨 launch 取中位数、
 * 预热次数<b>固定</b>（不随载荷变化）、每次迭代的结果参与 checksum（逃逸锚点）。</p>
 *
 * <p><b>用法</b>：</p>
 * <pre>
 * # 父进程：跑完整矩阵 + openssl 对照（输出一张表）
 * java -cp &lt;classpath&gt; io.edap.crypto.benchmark.TlsCipherProbe
 *
 * # 子进程：只测一个测点
 * java -cp &lt;classpath&gt; io.edap.crypto.benchmark.TlsCipherProbe --child \
 *       --alg=aes128gcm --shape=record --size=16384
 * </pre>
 *
 * <p><b>★ provider 归属必须一起看</b>：Solaris 的 JDK 可能把 {@code AES/GCM}
 * 解析到 {@code SunPKCS11}（走加密守护进程的 IPC），也可能解析到 {@code SUN}（纯软件）——
 * 两者的每记录固定成本能差一个数量级 ⇒ 本探针把 provider 名字一并打出来。</p>
 */
public final class TlsCipherProbe {

    /**
     * 结果行前缀 —— 父进程靠它从混有日志的 stdout 里捞出结果。
     */
    public static final String MARKER = "@@EDAP_TLS@@";

    /**
     * 形态：<b>TLS 的真实形态</b> —— 每条记录 {@code init + updateAAD + doFinal}。
     *
     * <p><b>源码依据</b>（JDK 17 {@code sun.security.ssl.SSLCipher}）：
     * {@code T12GcmWriteCipher.encrypt} 与 {@code T13GcmWriteCipher.encrypt}
     * 都是<b>每条记录</b>做这三步 ——</p>
     * <pre>
     * // T13GcmWriteCipher.encrypt（jdk17u SSLCipher.java:2049 / :2062 / :2082）
     * cipher.init(Cipher.ENCRYPT_MODE, key, spec, random);  // 每记录一个新 nonce ⇒ 重新 init
     * cipher.updateAAD(aad);                                 // content type + 长度 + 序号
     * len = cipher.doFinal(dup, bb);                         // 加密 + GHASH + tag
     * </pre>
     * <p>⇒ <b>「复用同一个 Cipher 逐条 update」不是 TLS 的形态</b>（那是 TLS 1.2 CBC
     * 的形态）；AEAD 每记录都要重新 init + 收尾。</p>
     */
    public static final String SHAPE_TLS = "tls";

    /**
     * 形态：每条消息重新 {@code getInstance + init + doFinal} —— 应用层「一密文一消息」
     * 的用法（edap-crypto 的 {@code CipherSession} 就是这个形态）。
     *
     * <p>比 {@link #SHAPE_TLS} 多付一次 {@code getInstance}（provider 查找）。</p>
     */
    public static final String SHAPE_ONESHOT = "oneshot";

    /**
     * 形态：<b>init 一次</b>，逐条 {@code update} —— <b>不是 TLS 的形态</b>，
     * 只作「上下文复用能省多少」的上界参考（TLS 1.2 CBC 走的就是这条路）。
     */
    public static final String SHAPE_CTX_REUSE = "ctxreuse";

    /**
     * TLS 的 AAD 长度（字节）—— TLS 1.2 GCM 是 13（序号 8 + type 1 + version 2 + 长度 2），
     * TLS 1.3 是 5（记录头）。取 13 ⇒ 偏保守。
     */
    private static final int AAD_LEN = 13;

    /**
     * 被测形态（按重要性排序 —— 表里第一行就是主口径）。
     */
    private static final String[] SHAPES = {SHAPE_TLS, SHAPE_ONESHOT, SHAPE_CTX_REUSE};

    /**
     * 载荷尺寸（字节）—— 1024 / <b>16384（TLS 记录大小）</b> / 65536。
     */
    private static final int[] SIZES = {1024, 16384, 65536};

    /**
     * 跨 launch 的轮次（每个测点起这么多次子 JVM）。
     */
    private static final int ROUNDS = Integer.getInteger("edap.crypto.tlscipher.rounds", 2);

    /**
     * 预热次数 —— <b>固定</b>，不随载荷变化。
     *
     * <p>HotSpot 的 Tier4 调用阈值是 5000 次 ⇒ 低于这个数，被测 lambda 停在 C1，
     * 读数里混着「代码编译到什么程度」这一项（见 memory
     * {@code feedback_benchmark_isolated_jvm}）。</p>
     */
    private static final int WARMUP = Integer.getInteger("edap.crypto.tlscipher.warmup", 10000);

    /**
     * 每批的字节预算 —— 折算迭代次数，使不同载荷的每批耗时量级可比。
     */
    private static final long BATCH_BYTES = Long.getLong("edap.crypto.tlscipher.bytes", 4L * 1024 * 1024);

    /**
     * 每批最少迭代次数（小载荷时防止批次太短、噪声压不住）。
     */
    private static final int MIN_ITERS = 32;

    /**
     * GCM 的 tag 长度（bit）。
     */
    private static final int TAG_BITS = 128;

    /**
     * 被测算法。
     */
    static final class Alg {

        /** 内部名字（命令行用）。 */
        final String name;

        /** 展示名。 */
        final String title;

        /** JCE transformation。 */
        final String transformation;

        /** key 的算法名（{@code SecretKeySpec} 用）。 */
        final String keyAlgorithm;

        /** key 长度（字节）。 */
        final int keyLength;

        /** openssl speed 里的算法名。 */
        final String opensslName;

        Alg(String name, String title, String transformation, String keyAlgorithm,
            int keyLength, String opensslName) {
            this.name = name;
            this.title = title;
            this.transformation = transformation;
            this.keyAlgorithm = keyAlgorithm;
            this.keyLength = keyLength;
            this.opensslName = opensslName;
        }

        /** 是否是 GCM（决定用 {@code GCMParameterSpec} 还是 {@code IvParameterSpec}）。 */
        boolean isGcm() {
            return transformation.toUpperCase(Locale.ROOT).contains("/GCM/");
        }
    }

    private static final Alg[] ALGS = {
            new Alg("aes128gcm", "AES-128-GCM", "AES/GCM/NoPadding", "AES", 16, "aes-128-gcm"),
            new Alg("aes256gcm", "AES-256-GCM", "AES/GCM/NoPadding", "AES", 32, "aes-256-gcm"),
            new Alg("chacha20poly1305", "ChaCha20-Poly1305", "ChaCha20-Poly1305", "ChaCha20", 32,
                    "chacha20-poly1305")
    };

    private TlsCipherProbe() {
        // 工具类
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "child")) {
            child(arg(args, "alg", "aes128gcm"),
                    arg(args, "shape", SHAPE_TLS),
                    Integer.parseInt(arg(args, "size", "16384")));
            return;
        }
        parent();
    }

    /**
     * 父进程模式的对外入口 —— 供 JUnit 用例调用。
     *
     * @return 报告文件
     */
    public static File run() throws Exception {
        return parent();
    }

    // ------------------------------------------------------------------
    // 父进程：跑完整矩阵 + openssl 对照
    // ------------------------------------------------------------------

    private static File parent() throws Exception {
        System.out.println("================ TLS 对称加密吞吐（JDK 侧）================");
        System.out.println("os.name     : " + System.getProperty("os.name"));
        System.out.println("os.arch     : " + System.getProperty("os.arch"));
        System.out.println("java.version: " + System.getProperty("java.version"));
        System.out.println("java.vendor : " + System.getProperty("java.vendor"));
        System.out.println("provider 归属（每条记录的固定成本由它决定）：");
        for (Alg alg : ALGS) {
            System.out.println("  " + pad(alg.title, 20) + " -> " + providerOf(alg));
        }
        System.out.println("================================================================");
        printCryptoFlags();

        // key/iv：固定内容 ⇒ 跨实现可比
        Map<String, List<Double>> samples = new LinkedHashMap<>();
        Map<String, String> providers = new LinkedHashMap<>();

        for (Alg alg : ALGS) {
            for (String shape : SHAPES) {
                for (int size : SIZES) {
                    String key = alg.name + "|" + shape + "|" + size;
                    for (int round = 0; round < ROUNDS; round++) {
                        String result = runChild(alg.name, shape, size);
                        double minNs = Double.parseDouble(field(result, "minNs"));
                        samples.computeIfAbsent(key, k -> new ArrayList<>()).add(minNs);
                        String provider = field(result, "provider");
                        if (provider != null && !provider.isEmpty()) {
                            providers.putIfAbsent(key, provider);
                        }
                    }
                }
            }
        }

        // ---- 汇总表 ----
        System.out.println();
        System.out.println("================ JDK 侧吞吐（每条记录 = 一次加密）================");
        System.out.printf(Locale.ROOT, "%-20s %-8s %8s %12s %10s %10s %10s  %s%n",
                "alg", "shape", "size", "ns/记录", "MB/s", "核@1Gbps", "核@10Gbps", "provider");
        System.out.println("------------------------------------------------------------------------------");

        List<String> jsonResults = new ArrayList<>();
        for (Alg alg : ALGS) {
            for (String shape : SHAPES) {
                for (int size : SIZES) {
                    String key = alg.name + "|" + shape + "|" + size;
                    Stats stats = stats(samples.get(key));
                    double mbps = stats.median > 0 ? size * 1000d / stats.median : 0d;
                    System.out.printf(Locale.ROOT, "%-20s %-8s %8d %12.1f %10.1f %10.2f %10.2f  %s%n",
                            alg.title, shape, size, stats.median, mbps,
                            cores(mbps, 1d), cores(mbps, 10d),
                            providers.getOrDefault(key, "?"));
                    System.out.printf(Locale.ROOT, "%-20s %-8s %8s %12s %10s %10s %10s  (CV %4.1f%%)%n",
                            "", "", "", "min " + fmt(stats.min), "", "", "", stats.cv);
                    jsonResults.add("{\"alg\":\"" + alg.name + "\",\"shape\":\"" + shape
                            + "\",\"size\":" + size
                            + ",\"nsPerRecord\":" + fmt(stats.median)
                            + ",\"mbps\":" + fmt(mbps)
                            + ",\"coresAt1Gbps\":" + fmt(cores(mbps, 1d))
                            + ",\"coresAt10Gbps\":" + fmt(cores(mbps, 10d))
                            + ",\"cv\":" + fmt(stats.cv)
                            + ",\"provider\":\"" + providers.getOrDefault(key, "") + "\"}");
                }
            }
        }
        System.out.println("------------------------------------------------------------------------------");
        System.out.println("（核@NGbps = 该链路上跑满加密要占几个核：线速字节数 ÷ 本行的 MB/s；"
                + "★ tls 形态 = TLS 的真实形态（每记录 init+updateAAD+doFinal）；"
                + "ctxreuse 不是 TLS 形态，只是上下文复用的上界参考）");

        // ---- OpenSSL 对照 ----
        System.out.println();
        System.out.println("================ OpenSSL 侧对照（openssl speed -evp）================");
        String openssl = findOpenssl();
        if (openssl == null) {
            System.out.println("未找到 openssl 可执行文件 ⇒ 没有对照列");
            System.out.println("（Solaris 上通常在 /opt/csw/bin/openssl 或 /usr/sfw/bin/openssl）");
        } else {
            System.out.println("openssl: " + openssl);
            for (Alg alg : ALGS) {
                OpenSslResult r = runOpenssl(openssl, alg.opensslName);
                System.out.println();
                System.out.println("--- " + alg.opensslName + " ---");
                System.out.println(r.raw);
                if (r.mbps > 0) {
                    System.out.printf(Locale.ROOT, "⇒ OpenSSL %s: %.1f MB/s（%d 字节块）"
                                    + "，核@1Gbps = %.2f，核@10Gbps = %.2f%n",
                            alg.title, r.mbps, r.blockSize, cores(r.mbps, 1d), cores(r.mbps, 10d));
                    System.out.println("   （口径：openssl speed -evp 每次迭代 = EVP_EncryptInit + Update + Final"
                            + " ⇒ 与上表的 tls / oneshot 形态同构，可直接对打；"
                            + "ctxreuse 行不可比 —— 那是上下文复用的上界）");
                } else {
                    System.out.println("⇒ 未能从 openssl 输出里解析出数值（原始输出见上）");
                }
            }
        }

        // ---- 报告落盘 ----
        return Report.write(jsonResults);
    }

    // ------------------------------------------------------------------
    // 子进程：只测一个测点
    // ------------------------------------------------------------------

    private static void child(String algName, String shape, int size) throws GeneralSecurityException {
        Alg alg = algByName(algName);
        byte[] payload = payload(size);
        byte[] key = key(alg.keyLength);
        byte[] iv = iv(12);

        Op op = createOp(alg, shape, key, iv);

        // ---- 预热（固定次数）----
        long checksum = 0;
        for (int i = 0; i < WARMUP; i++) {
            checksum += op.run(payload)[0];
        }

        // ---- 正式测量：若干批，批内取 min ----
        int iters = (int) Math.max(MIN_ITERS, BATCH_BYTES / Math.max(1, size));
        double[] nsPerOp = new double[5];
        for (int b = 0; b < nsPerOp.length; b++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                checksum += op.run(payload)[0];
            }
            long t1 = System.nanoTime();
            nsPerOp[b] = (double) (t1 - t0) / iters;
        }
        double[] sorted = nsPerOp.clone();
        Arrays.sort(sorted);

        System.out.println(MARKER + "{\"alg\":\"" + alg.name + "\""
                + ",\"shape\":\"" + shape + "\""
                + ",\"size\":" + size
                + ",\"iters\":" + iters
                + ",\"minNs\":" + fmt(sorted[0])
                + ",\"medianNs\":" + fmt(sorted[nsPerOp.length / 2])
                + ",\"provider\":\"" + providerOf(alg) + "\""
                + ",\"checksum\":" + checksum + "}");
    }

    /**
     * 起一个子 JVM 测一个测点，返回结果行。
     */
    private static String runChild(String alg, String shape, int size) throws Exception {
        String output = ProbeLauncher.run(TlsCipherProbe.class.getName(),
                Arrays.asList("--child", "--alg=" + alg, "--shape=" + shape, "--size=" + size));
        String result = ProbeLauncher.marker(output, MARKER);
        if (result == null) {
            throw new IllegalStateException("探针没有产出结果行（" + alg + "/" + shape + "/" + size + "）: " + output);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 被测操作
    // ------------------------------------------------------------------

    /**
     * 一次加密操作。
     */
    interface Op {
        byte[] run(byte[] payload) throws GeneralSecurityException;
    }

    /**
     * 造出被测形态对应的操作。
     *
     * @param shape {@link #SHAPE_TLS} / {@link #SHAPE_ONESHOT} / {@link #SHAPE_CTX_REUSE}
     */
    private static Op createOp(Alg alg, String shape, byte[] key, byte[] iv)
            throws GeneralSecurityException {
        if (SHAPE_ONESHOT.equals(shape)) {
            // 应用层「一密文一消息」：provider 查找 + init + 收尾，每条都重来一遍
            return payload -> {
                Cipher cipher = Cipher.getInstance(alg.transformation);
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, alg.keyAlgorithm),
                        alg.isGcm() ? new GCMParameterSpec(TAG_BITS, iv) : new IvParameterSpec(iv));
                return cipher.doFinal(payload);
            };
        }
        if (SHAPE_CTX_REUSE.equals(shape)) {
            // 上下文复用：init 一次，逐条 update（★ 不是 TLS 的形态，只是上界参考）
            final Cipher cipher = newCipher(alg);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, alg.keyAlgorithm),
                    alg.isGcm() ? new GCMParameterSpec(TAG_BITS, iv) : new IvParameterSpec(iv));
            return payload -> cipher.update(payload);
        }
        // tls：与 SSLEngine 的 AEAD 每记录路径同构 ——
        //   Cipher 对象复用（每连接 getInstance 一次），
        //   每条记录 init（新 nonce）+ updateAAD + doFinal
        //
        // ★ nonce 必须每记录不同：JDK 的 GaloisCounterMode 会拒绝「同一 key 下重用 IV」
        //   （InvalidAlgorithmParameterException: Cannot reuse iv for GCM encryption），
        //   而 TLS 1.3 本来就是「nonce = 固定 IV XOR 记录序号」
        //   （JDK 17 SSLCipher.T13GcmWriteCipher.encrypt 就是这么算的）。
        final Cipher cipher = newCipher(alg);
        final SecretKeySpec keySpec = new SecretKeySpec(key, alg.keyAlgorithm);
        final byte[] aad = aad();
        final byte[] baseIv = iv.clone();
        final long[] seq = {0L};
        return payload -> {
            byte[] nonce = recordNonce(baseIv, seq[0]++);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec,
                    alg.isGcm() ? new GCMParameterSpec(TAG_BITS, nonce) : new IvParameterSpec(nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(payload);
        };
    }

    /**
     * 每记录一个 nonce：{@code nonce = 固定 IV XOR 记录序号}（TLS 1.3 的算法）。
     */
    private static byte[] recordNonce(byte[] baseIv, long seq) {
        byte[] nonce = baseIv.clone();
        for (int i = 0; i < Long.BYTES && i < nonce.length; i++) {
            nonce[nonce.length - 1 - i] ^= (byte) (seq >>> (8 * i));
        }
        return nonce;
    }

    /**
     * 新建一个 {@link Cipher}（不 init）。
     */
    private static Cipher newCipher(Alg alg) {
        try {
            return Cipher.getInstance(alg.transformation);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("算法不可用: " + alg.transformation, e);
        }
    }

    /**
     * TLS 的 AAD（内容类型 + 长度 + 序号）—— 固定内容 ⇒ 跨实现可比。
     */
    private static byte[] aad() {
        byte[] aad = new byte[AAD_LEN];
        for (int i = 0; i < aad.length; i++) {
            aad[i] = (byte) (i * 11 + 3);
        }
        return aad;
    }

    // ------------------------------------------------------------------
    // OpenSSL 对照
    // ------------------------------------------------------------------

    /**
     * 打印与加密吞吐强相关的 JVM intrinsic 开关。
     *
     * <p><b>★ 少了这一列，数字没法解释</b>：JDK 的对称加密走硬件指令还是纯 Java
     * 软实现，吞吐能差一个数量级 —— 实测 macOS aarch64 的 Homebrew JDK 17
     * {@code UseAES=false} ⇒ AES-GCM 只有 ~68 MB/s，而同机 OpenSSL 是 ~8 GB/s。
     * Solaris/SPARC 上同理（{@code UseAES} / {@code UseSHA} 是否可用）——
     * 这个开关直接决定「TLS 要吃几个核」这个结论是乐观还是悲观。</p>
     *
     * <p>用子进程 {@code -XX:+PrintFlagsFinal -version} 读 ⇒ 不管父进程是怎么起的，
     * 都能拿到默认值。</p>
     */
    private static void printCryptoFlags() {
        String output = exec(ProbeLauncher.javaBin(), "-XX:+PrintFlagsFinal", "-version");
        System.out.println();
        System.out.println("JVM crypto intrinsic 开关（决定 JDK 侧走软实现还是硬件指令）：");
        if (output == null) {
            System.out.println("  （读不到 JVM flags）");
            return;
        }
        int printed = 0;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("bool Use")) {
                continue;
            }
            if (!containsAny(trimmed, "AES", "SHA", "GHASH", "GCM", "Vectorize", "ChaCha")) {
                continue;
            }
            System.out.println("  " + trimmed);
            printed++;
        }
        if (printed == 0) {
            System.out.println("  （本 JVM 没有相关的 intrinsic 开关 ⇒ 全部走软实现）");
        }
        System.out.println();
    }

    private static boolean containsAny(String s, String... needles) {
        for (String needle : needles) {
            if (s.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * OpenSSL 侧的吞吐读数。
     */
    static final class OpenSslResult {

        /** 原始输出。 */
        final String raw;

        /** MB/s（解析不出来时为 0）。 */
        final double mbps;

        /** 解析出来的块大小（字节）。 */
        final int blockSize;

        OpenSslResult(String raw, double mbps, int blockSize) {
            this.raw = raw;
            this.mbps = mbps;
            this.blockSize = blockSize;
        }
    }

    /**
     * 找 openssl 可执行文件。
     *
     * <p>Solaris 上的常见位置：{@code /opt/csw/bin/openssl}（CSW 包）、
     * {@code /usr/sfw/bin/openssl}（Sun FreeWare）。</p>
     */
    private static String findOpenssl() {
        List<String> candidates = new ArrayList<>(Arrays.asList(
                "openssl", "/opt/csw/bin/openssl", "/usr/sfw/bin/openssl", "/usr/bin/openssl"));
        for (String candidate : candidates) {
            if (candidate.contains("/")) {
                File file = new File(candidate);
                if (file.isFile() && file.canExecute()) {
                    return candidate;
                }
                continue;
            }
            String path = System.getenv("PATH");
            if (path == null) {
                continue;
            }
            for (String dir : path.split(File.pathSeparator)) {
                File file = new File(dir, candidate);
                if (file.isFile() && file.canExecute()) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /**
     * 跑 {@code openssl speed -evp <alg>} 并尽力解析出最大块那一列。
     */
    private static OpenSslResult runOpenssl(String binary, String algName) {
        String raw = exec(binary, "speed", "-evp", algName, "-seconds", "2");
        if (raw == null) {
            // 老版本 openssl 没有 -seconds ⇒ 去掉再来一次
            raw = exec(binary, "speed", "-evp", algName);
        }
        if (raw == null) {
            return new OpenSslResult("（openssl speed 执行失败）", 0d, 0);
        }
        double[] parsed = parseSpeed(raw, algName);
        return new OpenSslResult(raw, parsed[0], (int) parsed[1]);
    }

    /**
     * 执行命令并收集输出（stdout + stderr）。
     */
    private static String exec(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = readAll(in);
            }
            process.waitFor();
            return output;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 {@code openssl speed} 的输出里解析「最大块」那一列。
     *
     * <p>输出形如：</p>
     * <pre>
     * type             16 bytes     64 bytes    256 bytes   1024 bytes   8192 bytes  16384 bytes
     * AES-128-GCM      1234.56k    2345.67k    ...
     * </pre>
     *
     * @return {@code {MB/s, 块大小}}；解析不出来时 MB/s 为 0
     */
    private static double[] parseSpeed(String raw, String algName) {
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).startsWith("type ")) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length < 2 || !tokens[0].equalsIgnoreCase(algName)) {
                continue;
            }
            double lastK = 0d;
            for (int i = 1; i < tokens.length; i++) {
                String token = tokens[i];
                if (!token.endsWith("k")) {
                    continue;
                }
                try {
                    lastK = Double.parseDouble(token.substring(0, token.length() - 1));
                } catch (NumberFormatException ignore) {
                    // 不是数字 ⇒ 跳过
                }
            }
            if (lastK > 0d) {
                // openssl 的表头写明「The 'numbers' are in 1000s of bytes per second」
                // ⇒ k 值 ÷ 1000 得 MB/s；最后一列对应 16384 字节块
                return new double[]{lastK / 1000d, 16384d};
            }
        }
        return new double[]{0d, 0d};
    }

    // ------------------------------------------------------------------
    // 汇总
    // ------------------------------------------------------------------

    /**
     * 跨 launch 的汇总统计（<b>中位数</b>，不是 min —— 跨 launch 取 min 等于挑运气）。
     */
    static Stats stats(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double median = sorted[sorted.length / 2];
        double min = sorted[0];
        double max = sorted[sorted.length - 1];
        double mean = 0;
        for (double v : sorted) {
            mean += v;
        }
        mean /= sorted.length;
        double variance = 0;
        for (double v : sorted) {
            variance += (v - mean) * (v - mean);
        }
        variance /= sorted.length;
        double cv = mean > 0 ? Math.sqrt(variance) / mean * 100d : 0d;
        return new Stats(median, min, max, cv);
    }

    /**
     * 跑满线速要占几个核。
     *
     * @param mbps  该实现的吞吐（MB/s）
     * @param gbps  链路线速（Gbit/s）
     */
    static double cores(double mbps, double gbps) {
        if (mbps <= 0d) {
            return 0d;
        }
        return (gbps * 125d) / mbps;
    }

    static final class Stats {
        final double median;
        final double min;
        final double max;
        final double cv;

        Stats(double median, double min, double max, double cv) {
            this.median = median;
            this.min = min;
            this.max = max;
            this.cv = cv;
        }
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /**
     * 报告落盘（父进程）。
     */
    static final class Report {

        private Report() {
        }

        static File write(List<String> results) throws Exception {
            String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-");
            String arch = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-");
            String ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            File dir = new File("target/crypto-perf");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建报告目录: " + dir.getAbsolutePath());
            }
            File file = new File(dir, "tls-cipher-" + os + "-" + arch + "-" + ts + ".json");
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"osName\": \"").append(System.getProperty("os.name")).append("\",\n");
            sb.append("  \"osArch\": \"").append(System.getProperty("os.arch")).append("\",\n");
            sb.append("  \"javaVersion\": \"").append(System.getProperty("java.version")).append("\",\n");
            sb.append("  \"rounds\": ").append(ROUNDS).append(",\n");
            sb.append("  \"results\": [\n");
            for (int i = 0; i < results.size(); i++) {
                sb.append("    ").append(results.get(i));
                sb.append(i == results.size() - 1 ? "\n" : ",\n");
            }
            sb.append("  ]\n");
            sb.append("}\n");
            java.nio.file.Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println();
            System.out.println("报告: " + file.getAbsolutePath());
            return file;
        }
    }

    /**
     * provider 归属 —— 决定「每条记录的固定成本」由谁付。
     */
    static String providerOf(Alg alg) {
        try {
            Provider provider = Cipher.getInstance(alg.transformation).getProvider();
            return provider.getName() + " (" + provider.getClass().getSimpleName() + ")";
        } catch (Exception e) {
            return "不可用: " + e.getMessage();
        }
    }

    static Alg algByName(String name) {
        for (Alg alg : ALGS) {
            if (alg.name.equals(name)) {
                return alg;
            }
        }
        throw new IllegalArgumentException("未知 alg: " + name
                + "（支持 " + Arrays.toString(names()) + "）");
    }

    private static String[] names() {
        String[] names = new String[ALGS.length];
        for (int i = 0; i < ALGS.length; i++) {
            names[i] = ALGS[i].name;
        }
        return names;
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + 17) & 0x7f);
        }
        return data;
    }

    private static byte[] key(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 7 + 3);
        }
        return data;
    }

    private static byte[] iv(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 13 + 5);
        }
        return data;
    }

    /**
     * 读完子进程的输出（不用 {@code readAllBytes} —— 目标机上可能是老 JDK）。
     */
    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String field(String json, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*\"?([^,}\"]*)")
                .matcher(json);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String arg(String[] args, String name, String defaultValue) {
        String prefix = "--" + name + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return defaultValue;
    }

    /**
     * 是否带某个无值开关（如 {@code --child}）。
     */
    private static boolean hasFlag(String[] args, String name) {
        String flag = "--" + name;
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
