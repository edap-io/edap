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

package io.edap.crypto;

import io.edap.crypto.cipher.CipherProvider;
import io.edap.crypto.cipher.JceCipherProvider;
import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.JceDigestProvider;
import io.edap.crypto.digest.NativeDigestProvider;
import io.edap.crypto.internal.CryptoDiagnostics;
import io.edap.crypto.internal.CryptoSwitches;
import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.NativeMacProvider;
import io.edap.crypto.random.JceRandomProvider;
import io.edap.crypto.random.RandomProvider;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密码学实现的统一注册表。
 *
 * <p><b>两条注册通道</b>：</p>
 * <ol>
 *   <li><b>显式</b>：{@link #registerDigest} / {@link #registerMac} /
 *       {@link #registerCipher} / {@link #registerRandom}
 *       —— 同名注册<b>覆盖</b>（与 JWT 的 {@code AlgorithmRegistry} 同一形状）；</li>
 *   <li><b>自动发现</b>：{@link CryptoProviderFactory} 的 {@link ServiceLoader} SPI
 *       —— 适合把第三方实现插进来而不改调用方代码。</li>
 * </ol>
 *
 * <p><b>查找顺序</b>：显式/自动注册的 provider → 按开关解析出的默认 provider。</p>
 *
 * <p><b>默认 provider 的解析</b>（每个算法只解析一次并缓存）：</p>
 * <ol>
 *   <li>开关要求 native 且 native 可用 ⇒ native provider；</li>
 *   <li>开关要求 native 但不可用 ⇒ 严格模式抛 {@link CryptoUnavailableException}，
 *       否则打<b>一次</b> WARN 后降级到 JCE；</li>
 *   <li>显式 {@code edap.crypto.<kind>.provider=jce} ⇒ 强制 JCE（不告警）。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：内部全部用 {@link ConcurrentHashMap}。</p>
 */
public final class CryptoRegistry {

    private static final Map<String, DigestProvider> DIGESTS = new ConcurrentHashMap<>();
    private static final Map<String, MacProvider> MACS = new ConcurrentHashMap<>();
    private static final Map<String, CipherProvider> CIPHERS = new ConcurrentHashMap<>();
    private static final Map<String, RandomProvider> RANDOMS = new ConcurrentHashMap<>();

    /**
     * 默认 provider 的解析缓存（按算法名）。
     */
    private static final Map<String, DigestProvider> DEFAULT_DIGESTS = new ConcurrentHashMap<>();
    private static final Map<String, MacProvider> DEFAULT_MACS = new ConcurrentHashMap<>();
    private static final Map<String, CipherProvider> DEFAULT_CIPHERS = new ConcurrentHashMap<>();
    private static final Map<String, RandomProvider> DEFAULT_RANDOMS = new ConcurrentHashMap<>();

    static {
        loadServiceProviders();
    }

    private CryptoRegistry() {
        // 工具类，禁止实例化
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 注册摘要 provider（同名覆盖）。
     *
     * @throws IllegalArgumentException 名字为空或 provider 为 null
     */
    public static void registerDigest(String algorithm, DigestProvider provider) {
        requireName(algorithm, "digest algorithm");
        requireProvider(provider, "digest provider");
        DIGESTS.put(algorithm, provider);
    }

    /**
     * 注册 MAC provider（同名覆盖）。
     */
    public static void registerMac(String algorithm, MacProvider provider) {
        requireName(algorithm, "mac algorithm");
        requireProvider(provider, "mac provider");
        MACS.put(algorithm, provider);
    }

    /**
     * 注册 cipher provider（同名覆盖）。
     */
    public static void registerCipher(String name, CipherProvider provider) {
        requireName(name, "cipher name");
        requireProvider(provider, "cipher provider");
        CIPHERS.put(name, provider);
    }

    /**
     * 注册随机数 provider（同名覆盖）。
     */
    public static void registerRandom(String name, RandomProvider provider) {
        requireName(name, "random name");
        requireProvider(provider, "random provider");
        RANDOMS.put(name, provider);
    }

    // ------------------------------------------------------------------ 查找

    /**
     * 查找摘要 provider。
     *
     * <p>未注册时返回按开关解析出的默认 provider（native 可用则 native，否则 JCE）。</p>
     */
    public static DigestProvider digest(String algorithm) {
        requireName(algorithm, "digest algorithm");
        DigestProvider provider = DIGESTS.get(algorithm);
        if (provider != null) {
            return provider;
        }
        return DEFAULT_DIGESTS.computeIfAbsent(algorithm, CryptoRegistry::resolveDigest);
    }

    /**
     * 查找 MAC provider。
     */
    public static MacProvider mac(String algorithm) {
        requireName(algorithm, "mac algorithm");
        MacProvider provider = MACS.get(algorithm);
        if (provider != null) {
            return provider;
        }
        return DEFAULT_MACS.computeIfAbsent(algorithm, CryptoRegistry::resolveMac);
    }

    /**
     * 查找 cipher provider。
     */
    public static CipherProvider cipher(String name) {
        requireName(name, "cipher name");
        CipherProvider provider = CIPHERS.get(name);
        if (provider != null) {
            return provider;
        }
        return DEFAULT_CIPHERS.computeIfAbsent(name, CryptoRegistry::resolveCipher);
    }

    /**
     * 查找随机数 provider。
     */
    public static RandomProvider random(String name) {
        requireName(name, "random name");
        RandomProvider provider = RANDOMS.get(name);
        if (provider != null) {
            return provider;
        }
        return DEFAULT_RANDOMS.computeIfAbsent(name, CryptoRegistry::resolveRandom);
    }

    /** 已注册的摘要算法名（不可修改视图） */
    public static Set<String> digestNames() {
        return Collections.unmodifiableSet(DIGESTS.keySet());
    }

    /** 已注册的 MAC 算法名（不可修改视图） */
    public static Set<String> macNames() {
        return Collections.unmodifiableSet(MACS.keySet());
    }

    /** 已注册的 cipher 名（不可修改视图） */
    public static Set<String> cipherNames() {
        return Collections.unmodifiableSet(CIPHERS.keySet());
    }

    /** 已注册的随机数实现名（不可修改视图） */
    public static Set<String> randomNames() {
        return Collections.unmodifiableSet(RANDOMS.keySet());
    }

    // ------------------------------------------------------------------ 内部

    private static DigestProvider resolveDigest(String algorithm) {
        String forced = CryptoSwitches.forcedProvider(CryptoKind.DIGEST);
        if ("jce".equals(forced)) {
            return new JceDigestProvider(algorithm);
        }
        if (CryptoSwitches.nativeEnabled(CryptoKind.DIGEST)) {
            DigestProvider nativeProvider = NativeDigestProvider.tryCreate(algorithm);
            if (nativeProvider != null) {
                return nativeProvider;
            }
            if ("native".equals(forced) || CryptoSwitches.isStrict()) {
                throw new CryptoUnavailableException("native digest unavailable for " + algorithm
                        + " (edap-native not on classpath or no prebuilt .o for this platform)");
            }
            CryptoSwitches.warnOnce("digest:" + algorithm,
                    "native digest unavailable for " + algorithm + ", falling back to JCE");
        }
        return new JceDigestProvider(algorithm);
    }

    private static MacProvider resolveMac(String algorithm) {
        String forced = CryptoSwitches.forcedProvider(CryptoKind.MAC);
        if ("jce".equals(forced)) {
            return new JceMacProvider(algorithm);
        }
        if (CryptoSwitches.nativeEnabled(CryptoKind.MAC)) {
            MacProvider nativeProvider = NativeMacProvider.tryCreate(algorithm);
            if (nativeProvider != null) {
                return nativeProvider;
            }
            if ("native".equals(forced) || CryptoSwitches.isStrict()) {
                throw new CryptoUnavailableException("native mac unavailable for " + algorithm
                        + " (edap-native not on classpath or no prebuilt .o for this platform)");
            }
            CryptoSwitches.warnOnce("mac:" + algorithm,
                    "native mac unavailable for " + algorithm + ", falling back to JCE");
        }
        return new JceMacProvider(algorithm);
    }

    private static CipherProvider resolveCipher(String name) {
        // v1：cipher 只走 JCE。native cipher 需要 EVP，且 OpenSSL 3.x 的 provider dispatch
        // 可能吃掉全部收益 —— 是否上 native 由实测决定（见 C-9）。
        return JceCipherProvider.forTransformation(name);
    }

    private static RandomProvider resolveRandom(String name) {
        // v1：random 只走 JCE —— JDK 的 SecureRandom 默认就是 OS 原生熵源，
        // JNI RAND_bytes 收益 ≈ 0、风险大（见 CRYPTO_DESIGN.md §random）。
        return new JceRandomProvider(name);
    }

    /**
     * {@link ServiceLoader} 自动发现（第二注册通道）。
     */
    private static void loadServiceProviders() {
        try {
            for (CryptoProviderFactory factory : ServiceLoader.load(CryptoProviderFactory.class)) {
                CryptoProvider provider = factory.create();
                if (provider == null) {
                    continue;
                }
                switch (provider.kind()) {
                    case DIGEST:
                        registerDigest(provider.algorithm(), (DigestProvider) provider);
                        break;
                    case MAC:
                        registerMac(provider.algorithm(), (MacProvider) provider);
                        break;
                    case CIPHER:
                        registerCipher(provider.algorithm(), (CipherProvider) provider);
                        break;
                    case RANDOM:
                        registerRandom(provider.algorithm(), (RandomProvider) provider);
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable t) {
            CryptoDiagnostics.warn("failed to load CryptoProviderFactory via ServiceLoader: " + t);
        }
    }

    private static void requireName(String name, String what) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(what + " required");
        }
    }

    private static void requireProvider(Object provider, String what) {
        if (provider == null) {
            throw new IllegalArgumentException(what + " required");
        }
    }

    /**
     * 清空注册表与默认缓存 —— <b>仅供测试</b>。
     *
     * <p>单测需要在同一 JVM 里反复切换系统属性并重新解析默认 provider。</p>
     *
     * <p>清空后<b>重新跑一遍 ServiceLoader 发现</b>：这样注册表回到「刚 bootstrap 完」
     * 的状态 —— SPI 自动发现的 provider 仍在，显式注册的被清掉了。
     * 不重跑的话，{@code resetForTest} 之后 SPI 通道就永久失效了。</p>
     */
    public static void resetForTest() {
        DIGESTS.clear();
        MACS.clear();
        CIPHERS.clear();
        RANDOMS.clear();
        DEFAULT_DIGESTS.clear();
        DEFAULT_MACS.clear();
        DEFAULT_CIPHERS.clear();
        DEFAULT_RANDOMS.clear();
        CryptoSwitches.resetWarningsForTest();
        loadServiceProviders();
    }

    /**
     * 已注册 provider 的诊断快照（仅供测试/诊断）。
     */
    public static List<String> describe() {
        return List.of("digest=" + DIGESTS.keySet(), "mac=" + MACS.keySet(),
                "cipher=" + CIPHERS.keySet(), "random=" + RANDOMS.keySet());
    }
}
