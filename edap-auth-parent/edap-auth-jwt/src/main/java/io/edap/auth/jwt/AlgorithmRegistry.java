package io.edap.auth.jwt;

import io.edap.auth.jwt.algorithm.HmacSha256;
import io.edap.auth.jwt.algorithm.HmacSha256Native;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 算法工厂注册表：按 {@code alg} 名（如 "HS256" / "RS256"）查找 {@link Algorithm} 工厂。
 *
 * <p>线程安全（{@link ConcurrentHashMap}）。</p>
 *
 * <p><b>安全</b>：{@link #getFactory(String)} 显式拒绝 {@code "none"} 算法（无论大小写），
 * 防止 JWT {@code alg=none} 绕过签名验证的经典攻击。</p>
 *
 * <p><b>HS256 native 切换</b>：默认行为 —— edap-native 在 classpath 且
 * {@code io.edap.jni.Native.ENABLE_NATIVE=true} 时，HS256 工厂自动用
 * {@link HmacSha256Native}（OpenSSL JNI）；否则用 {@link HmacSha256}（Java）。
 * 显式 {@code -Dedap.jwt.hmac.native=false} 强制走 Java。检测由
 * {@link HmacSha256Native#isAvailable()} 完成。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * // 应用层注册额外算法
 * AlgorithmRegistry.register("RS256", RsaSha256::new);
 *
 * // 派发：AlgorithmRegistry 找工厂 → KeyCache 缓存实例
 * Function&lt;String, Algorithm&gt; factory = AlgorithmRegistry.getFactory(alg);
 * if (factory == null) { // 不支持
 *     throw new UnsupportedAlgorithmException(alg);
 * }
 * Algorithm instance = keyCache.getOrCreate(alg, signKey, factory);
 * }</pre>
 */
public final class AlgorithmRegistry {

    private static final Map<String, Function<String, Algorithm>> FACTORIES = new ConcurrentHashMap<>();

    static {
        // 默认走 native（edap-native 在 classpath + 平台 .o 就绪）；仅显式禁用或探测失败时 fallback
        Function<String, Algorithm> hs256Factory =
                HmacSha256Native.isAvailable() ? HmacSha256Native::new : HmacSha256::new;
        register("HS256", hs256Factory);
    }

    private AlgorithmRegistry() {
        // 工具类，禁止实例化
    }

    /**
     * 注册算法工厂。同名已有注册将被覆盖（应用可借此替换内置实现）。
     *
     * @param name    算法名（HS256 / RS256 / ES256 / EdDSA 等）
     * @param factory 工厂：接收密钥字符串，返回 Algorithm 实例
     * @throws IllegalArgumentException name 或 factory 为 null/空
     */
    public static void register(String name, Function<String, Algorithm> factory) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("algorithm name required");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory required");
        }
        FACTORIES.put(name, factory);
    }

    /**
     * 获取算法工厂。
     *
     * @param name 算法名
     * @return 工厂；{@code null} 表示该算法未注册
     * @throws SecurityException 算法名为 {@code "none"}（无论大小写）—— 强制拒绝
     */
    public static Function<String, Algorithm> getFactory(String name) {
        if ("none".equalsIgnoreCase(name)) {
            throw new SecurityException("'none' algorithm is not allowed");
        }
        return FACTORIES.get(name);
    }

    /** 当前已注册的算法名（不可修改视图，仅用于诊断） */
    public static Set<String> names() {
        return Collections.unmodifiableSet(FACTORIES.keySet());
    }
}