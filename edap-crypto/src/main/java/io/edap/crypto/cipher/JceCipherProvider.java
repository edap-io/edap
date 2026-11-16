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

package io.edap.crypto.cipher;

import io.edap.crypto.internal.InstancePool;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCE 对称加密实现 —— JDK 内置 {@link Cipher}。
 *
 * <p><b>ThreadLocal 复用</b>：{@code Cipher.getInstance} 每次 1~3μs，
 * {@link Cipher} 非线程安全 ⇒ provider 内部用 {@link InstancePool} 每线程复用一个实例。</p>
 *
 * <p><b>★ 实例必须被 session 独占</b>：{@code Cipher.init(opmode, key, iv)} 会重置
 * 内部状态。同一线程里两个活 session 共用一个实例时，后一个的 {@code init} 会把前一个
 * 的 key/iv 覆盖掉 ⇒ 前一个会用<b>别人的 key</b> 加密，而且不报错。
 * 所以每个 session 独占自己的实例，用完（{@code doFinal()}）再还回池子。</p>
 *
 * <p><b>★★ 同一把 key 复用同一个 {@link SecretKeySpec} 对象</b>：
 * Solaris 上 {@code HmacSHA256} 落在 {@code SunPKCS11-Solaris}，key 必须<b>导进 token</b>；
 * 而 <b>JDK 的 token 导入缓存是按 key <i>对象身份</i>走的，跟字节无关</b>
 * （实测：init 同一个 key 对象 10.4μs vs 每次 new 一个字节相同的对象 133.7μs，<b>差 12.9 倍</b>）。
 * 早先的实现每次操作都 {@code new SecretKeySpec(...)}
 * ⇒ 每次 {@code init} 都要把 key 重新导一次 token。
 * ⇒ 本实现让池里的实例<b>记住自己用的是哪把 key</b>（见 {@link KeyedCipher}）：
 * 同一把 key 直接复用<b>同一个</b> {@link SecretKeySpec} 对象 ⇒ JDK 的导入缓存能命中。
 * （cipher 与 {@code Mac} 的差别：<b>cipher 的 {@code init} 带 IV，躲不掉</b> ——
 * AEAD 每条消息必须换 nonce ⇒ 这里只复用 key 对象，不复用整个 init。）</p>
 *
 * <p><b>AEAD</b>：GCM 用 {@link GCMParameterSpec}；tag 校验失败时 JCE 抛
 * {@code AEADBadTagException} —— 本层<b>原样透出</b>，调用方据此区分
 * 「密文被篡改」与「IO 错误」。</p>
 *
 * <p><b>★★ GCM 的 nonce 复用会被 JDK 拒绝（调用方要注意）</b>：
 * SunJCE 的 {@code GaloisCounterMode} 在<b>同一个 {@link Cipher} 实例</b>上
 * 用<b>同一个 IV</b> 做第二次加密时会抛
 * {@code InvalidAlgorithmParameterException: Cannot reuse iv for GCM encryption}。
 * 由于本实现按线程池化实例，<b>这个异常是否发生取决于调用落在哪个线程</b>
 * （同线程 ⇒ 抛；换线程 ⇒ 拿到另一个实例，不抛）。
 * ⇒ <b>调用方绝不能复用 nonce</b>（那本来就是 GCM 的硬性要求）；
 * 本层不额外加「禁止 nonce 复用」的检查，因为那需要跨线程的共享状态。</p>
 */
public class JceCipherProvider implements CipherProvider {

    private final String transformation;

    private final String keyAlgorithm;

    private final int keyLength;

    private final int ivLength;

    private final int tagLengthBits;

    private final boolean aead;

    private final InstancePool<KeyedCipher> pool;

    /**
     * 至今调用过几次 {@code Cipher.init} —— <b>仅供测试</b>。
     *
     * <p>cipher 的 init 带 IV，而 <b>AEAD 每条消息必须换 nonce</b> ⇒ init 躲不掉，
     * 次数必然是 N（那是语义要求，不是缺陷）。真正要守的是
     * {@link #keySpecCounter} —— 「同一把 key ⇒ 同一个 key 对象」。</p>
     */
    private final AtomicInteger initCounter = new AtomicInteger();

    /**
     * 至今构造过几个 {@link SecretKeySpec} —— <b>仅供测试</b>。
     *
     * <p><b>为什么这个数就是「同一把 key ⇒ 同一个 key 对象」</b>：
     * JDK 的 PKCS#11 token 导入缓存按 key <b>对象身份</b>走，跟字节无关
     * ⇒ 只要每次操作都 {@code new SecretKeySpec(...)}，每次 init 都要重新导一次 key
     * （Solaris 上实测差 12.9 倍）。这个计数器数「造了几个 key 对象」，
     * 与「传给 init 的是不是同一个对象」等价。</p>
     */
    private final AtomicInteger keySpecCounter = new AtomicInteger();

    /**
     * @param transformation JCE transformation（如 {@code "AES/GCM/NoPadding"}）
     * @param keyLength      key 长度（bit）
     * @param ivLength       IV 长度（字节）
     * @param tagLengthBits  认证 tag 长度（bit）；非 AEAD 传 0
     */
    public JceCipherProvider(String transformation, int keyLength, int ivLength, int tagLengthBits) {
        if (transformation == null || transformation.isEmpty()) {
            throw new IllegalArgumentException("cipher transformation required");
        }
        if (keyLength <= 0 || keyLength % 8 != 0) {
            throw new IllegalArgumentException("invalid key length (bits): " + keyLength);
        }
        this.transformation = transformation;
        this.keyAlgorithm = transformation.split("/")[0];
        this.keyLength = keyLength;
        this.ivLength = ivLength;
        this.tagLengthBits = tagLengthBits;
        this.aead = transformation.toUpperCase(java.util.Locale.ENGLISH).contains("/GCM/");
        this.pool = new InstancePool<>(() -> {
            try {
                return new KeyedCipher(Cipher.getInstance(transformation), keySpecCounter);
            } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
                throw new IllegalArgumentException("unsupported cipher transformation: " + transformation, e);
            }
        });
        // 立刻建一个实例 —— transformation 不支持时在构造期就失败，而不是等到第一次调用
        pool.release(pool.acquire());
    }

    /**
     * 按 transformation 推断参数创建 provider（AES-256 / GCM 12 字节 IV / 128 bit tag）。
     *
     * <p>需要 AES-128 等其它参数时直接用构造函数显式指定。</p>
     */
    public static JceCipherProvider forTransformation(String transformation) {
        String upper = transformation.toUpperCase(java.util.Locale.ENGLISH);
        boolean aead = upper.contains("/GCM/");
        int ivLength = aead ? 12 : 16;
        int tagLength = aead ? 128 : 0;
        return new JceCipherProvider(transformation, 256, ivLength, tagLength);
    }

    @Override
    public String algorithm() {
        return transformation;
    }

    @Override
    public String getTransformation() {
        return transformation;
    }

    @Override
    public int getKeyLength() {
        return keyLength;
    }

    @Override
    public int getIvLength() {
        return ivLength;
    }

    @Override
    public int getTagLengthBits() {
        return tagLengthBits;
    }

    @Override
    public boolean isAead() {
        return aead;
    }

    /**
     * 至今调用过几次 {@code Cipher.init} —— <b>仅供测试</b>。
     */
    int initCount() {
        return initCounter.get();
    }

    /**
     * 至今构造过几个 {@link SecretKeySpec} —— <b>仅供测试</b>。
     *
     * <p>同一把 key 连续多次操作 ⇒ 这个数不涨 ⇒ 传给 init 的一直是<b>同一个</b> key 对象。</p>
     */
    int keySpecCount() {
        return keySpecCounter.get();
    }

    @Override
    public CipherSession newSession(byte[] key, byte[] iv, int opmode) throws GeneralSecurityException {
        if (key == null) {
            throw new InvalidKeyException("cipher key required");
        }
        if (key.length * 8 != keyLength) {
            throw new InvalidKeyException("expected key length " + keyLength + " bits, got " + (key.length * 8));
        }
        if (iv == null || iv.length != ivLength) {
            throw new InvalidAlgorithmParameterException(
                    "expected iv length " + ivLength + " bytes, got " + (iv == null ? 0 : iv.length));
        }
        return new JceCipherSession(pool, keyAlgorithm, key.clone(), iv.clone(), opmode, aead, tagLengthBits,
                initCounter);
    }

    /**
     * 池里的实例 —— 除了 {@link Cipher} 本身，还记住「当前用的是哪把 key」。
     *
     * <p><b>为什么需要记</b>：JDK 的 PKCS#11 token 导入缓存按 key <b>对象身份</b>走，
     * 跟字节无关 ⇒ 每次操作都 {@code new SecretKeySpec(...)} 就等于每次都重新导一次 key
     * （Solaris 上实测差 12.9 倍）。让实例记住自己的 key，同一把 key 就复用同一个
     * {@link SecretKeySpec} 对象 ⇒ JDK 的导入缓存能命中。</p>
     *
     * <p><b>线程安全</b>：实例由 {@link InstancePool} 按线程独占，
     * 同一时刻只有一个线程碰它 ⇒ 无需同步。</p>
     */
    private static final class KeyedCipher {

        private final Cipher cipher;

        /**
         * key 对象计数计数器（<b>仅供测试</b>）—— 与 provider 共用同一个。
         */
        private final AtomicInteger keySpecCounter;

        /**
         * 当前 {@link #keySpec} 对应的 key 字节；{@code null} 表示还没建过。
         */
        private byte[] key;

        /**
         * 由 {@link #key} 构造出来的 {@link SecretKeySpec}（<b>复用同一个对象</b>）。
         */
        private SecretKeySpec keySpec;

        KeyedCipher(Cipher cipher, AtomicInteger keySpecCounter) {
            this.cipher = cipher;
            this.keySpecCounter = keySpecCounter;
        }

        /**
         * 拿到「这把 key 对应的 {@link SecretKeySpec}」—— 同一把 key 返回<b>同一个对象</b>。
         */
        SecretKeySpec keySpec(byte[] candidate, String algorithm) {
            if (key != null && Arrays.equals(key, candidate)) {
                return keySpec;
            }
            SecretKeySpec spec = new SecretKeySpec(candidate, algorithm);
            keySpecCounter.incrementAndGet();
            this.keySpec = spec;
            this.key = candidate.clone();
            return spec;
        }
    }

    /**
     * JCE 加/解密会话 —— 独占一个 {@link Cipher} 实例。
     */
    static final class JceCipherSession implements CipherSession {

        private final InstancePool<KeyedCipher> pool;

        private final String keyAlgorithm;

        private final byte[] key;

        private final byte[] iv;

        private final int opmode;

        private final boolean aead;

        private final int tagLengthBits;

        /**
         * init 次数计数器（<b>仅供测试</b>）—— 与 provider 共用同一个。
         */
        private final AtomicInteger initCounter;

        /**
         * 当前独占的实例；{@code null} 表示还没借（用完已归还）。
         */
        private KeyedCipher keyed;

        JceCipherSession(InstancePool<KeyedCipher> pool, String keyAlgorithm, byte[] key, byte[] iv,
                int opmode, boolean aead, int tagLengthBits, AtomicInteger initCounter) {
            this.pool = pool;
            this.keyAlgorithm = keyAlgorithm;
            this.key = key;
            this.iv = iv;
            this.opmode = opmode;
            this.aead = aead;
            this.tagLengthBits = tagLengthBits;
            this.initCounter = initCounter;
        }

        private KeyedCipher keyed() throws GeneralSecurityException {
            if (keyed == null) {
                KeyedCipher instance = pool.acquire();
                try {
                    // 同一把 key ⇒ 同一个 SecretKeySpec 对象 ⇒ JDK 的 token 导入缓存能命中
                    SecretKeySpec secretKey = instance.keySpec(key, keyAlgorithm);
                    initCounter.incrementAndGet();
                    if (aead) {
                        instance.cipher.init(opmode, secretKey, new GCMParameterSpec(tagLengthBits, iv));
                    } else {
                        instance.cipher.init(opmode, secretKey, new IvParameterSpec(iv));
                    }
                } catch (GeneralSecurityException e) {
                    pool.release(instance);
                    throw e;
                }
                keyed = instance;
            }
            return keyed;
        }

        @Override
        public byte[] doFinal(byte[] input) throws GeneralSecurityException {
            KeyedCipher instance = keyed();
            try {
                return instance.cipher.doFinal(input);
            } finally {
                // 认证失败（AEADBadTagException）也要归还实例，否则池子少一个
                pool.release(instance);
                keyed = null;
            }
        }

        @Override
        public byte[] doFinal(byte[] input, int offset, int len) throws GeneralSecurityException {
            KeyedCipher instance = keyed();
            try {
                return instance.cipher.doFinal(input, offset, len);
            } finally {
                pool.release(instance);
                keyed = null;
            }
        }

        @Override
        public void close() {
            if (keyed != null) {
                pool.release(keyed);
                keyed = null;
            }
        }
    }
}
