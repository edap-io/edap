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
 * <p><b>AEAD</b>：GCM 用 {@link GCMParameterSpec}；tag 校验失败时 JCE 抛
 * {@code AEADBadTagException} —— 本层<b>原样透出</b>，调用方据此区分
 * 「密文被篡改」与「IO 错误」。</p>
 */
public class JceCipherProvider implements CipherProvider {

    private final String transformation;

    private final String keyAlgorithm;

    private final int keyLength;

    private final int ivLength;

    private final int tagLengthBits;

    private final boolean aead;

    private final InstancePool<Cipher> pool;

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
                return Cipher.getInstance(transformation);
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
        return new JceCipherSession(pool, keyAlgorithm, key.clone(), iv.clone(), opmode, aead, tagLengthBits);
    }

    /**
     * JCE 加/解密会话 —— 独占一个 {@link Cipher} 实例。
     */
    static final class JceCipherSession implements CipherSession {

        private final InstancePool<Cipher> pool;

        private final String keyAlgorithm;

        private final byte[] key;

        private final byte[] iv;

        private final int opmode;

        private final boolean aead;

        private final int tagLengthBits;

        /**
         * 当前独占的实例；{@code null} 表示还没借（用完已归还）。
         */
        private Cipher cipher;

        JceCipherSession(InstancePool<Cipher> pool, String keyAlgorithm, byte[] key, byte[] iv,
                int opmode, boolean aead, int tagLengthBits) {
            this.pool = pool;
            this.keyAlgorithm = keyAlgorithm;
            this.key = key;
            this.iv = iv;
            this.opmode = opmode;
            this.aead = aead;
            this.tagLengthBits = tagLengthBits;
        }

        private Cipher cipher() throws GeneralSecurityException {
            if (cipher == null) {
                Cipher instance = pool.acquire();
                try {
                    SecretKeySpec secretKey = new SecretKeySpec(key, keyAlgorithm);
                    if (aead) {
                        instance.init(opmode, secretKey, new GCMParameterSpec(tagLengthBits, iv));
                    } else {
                        instance.init(opmode, secretKey, new IvParameterSpec(iv));
                    }
                } catch (GeneralSecurityException e) {
                    pool.release(instance);
                    throw e;
                }
                cipher = instance;
            }
            return cipher;
        }

        @Override
        public byte[] doFinal(byte[] input) throws GeneralSecurityException {
            Cipher instance = cipher();
            try {
                return instance.doFinal(input);
            } finally {
                // 认证失败（AEADBadTagException）也要归还实例，否则池子少一个
                pool.release(instance);
                cipher = null;
            }
        }

        @Override
        public byte[] doFinal(byte[] input, int offset, int len) throws GeneralSecurityException {
            Cipher instance = cipher();
            try {
                return instance.doFinal(input, offset, len);
            } finally {
                pool.release(instance);
                cipher = null;
            }
        }

        @Override
        public void close() {
            if (cipher != null) {
                pool.release(cipher);
                cipher = null;
            }
        }
    }
}
