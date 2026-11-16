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

package io.edap.crypto.test.spi;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 能看见「传给 {@code init} 的是哪个 key <b>对象</b>」的 {@link Cipher} ——
 * 用来守住「同一把 key 要复用同一个 {@code SecretKeySpec} 对象」这条<b>结构性</b>不变量。
 *
 * <p><b>为什么这条要守</b>：Solaris 上 {@code HmacSHA256} 落在 {@code SunPKCS11-Solaris}，
 * key 必须导进 token，而 <b>JDK 的 token 导入缓存按 key <i>对象身份</i>走、跟字节无关</b>
 * ⇒ 每次 {@code new SecretKeySpec(...)} 就等于每次重新导一次 key。
 * （cipher 侧是同一条机制，只是 {@code init} 带 IV、躲不掉，只能复用 key 对象。）</p>
 *
 * <p><b>为什么不能靠测时间</b>：开发机（SunJCE 软件路径）上这条路径几乎不要钱，
 * Solaris 上却要 ~130μs ⇒ <b>同一个缺陷在两台机器上的时间差是 3000 倍</b>，
 * 时间断言要么在开发机上永远绿、要么在 Solaris 上永远 flaky。
 * 能跨平台守住它的只有「init 被调了几次 / 见过几个 key 对象」这个结构性事实。</p>
 *
 * <p><b>做法</b>：往 JCE provider 列表<b>头部</b>插一个自己的 provider，
 * {@code Cipher.getInstance("AES/GCM/NoPadding")} 就会拿到它；它把真正的计算转交给
 * 「另一个」真实 {@link Cipher}，自己只记 {@code engineInit} 的次数与 key 对象身份。</p>
 *
 * <p><b>实现细节</b>：{@code Provider.Service} 在 JDK 17 上只认<b>无参构造</b>
 * （{@code setConstructorParameter} 不存在），所以替身与计数器只能由 SPI
 * 自己去 {@link Security} 里找 —— 按 provider 名字把自己排除掉即可。</p>
 */
public final class CountingCipherProvider extends Provider {

    /**
     * provider 名字（{@link Security#removeProvider(String)} 用它摘掉）。
     */
    public static final String NAME = "EdapCountingCipher";

    /**
     * 被接管的 transformation —— 与 {@code JceCipherProvider} 用的一致。
     */
    public static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * GCM 的 tag 长度（bit）。
     */
    public static final int TAG_BITS = 128;

    /**
     * 被统计的 {@code engineInit} 次数。
     */
    private final AtomicInteger initCount = new AtomicInteger();

    /**
     * 见过多少个<b>不同的 key 对象</b>（按对象身份，不是按字节）。
     *
     * <p>用 {@link IdentityHashMap} 的原因正是这里要守的不变量：
     * 按 {@code equals} 比的话，{@code SecretKeySpec} 的 {@code equals} 比的是
     * <b>字节</b>，那就分不出「同一个对象」与「字节相同但对象不同」了。</p>
     */
    private final Set<Key> distinctKeys = Collections.newSetFromMap(new IdentityHashMap<>());

    public CountingCipherProvider() {
        super(NAME, "1.0", "数 init 次数 / 记 key 对象身份的 Cipher（测试用）");
        putService(new Service(this, "Cipher", TRANSFORMATION,
                CountingCipherSpi.class.getName(), null, null));
    }

    /**
     * 至今被 {@code init} 了几次。
     */
    public int initCount() {
        return initCount.get();
    }

    /**
     * 至今见过几个<b>不同的 key 对象</b>。
     */
    public int distinctKeyCount() {
        return distinctKeys.size();
    }

    /**
     * 真正的 {@link CipherSpi} —— 只加了一层计数/记录，其余全部转交。
     */
    public static final class CountingCipherSpi extends CipherSpi {

        private final Cipher delegate;

        private final AtomicInteger initCount;

        private final Set<Key> distinctKeys;

        public CountingCipherSpi() throws NoSuchAlgorithmException, NoSuchPaddingException {
            this.delegate = findDelegate();
            Provider owner = Security.getProvider(NAME);
            if (owner == null) {
                throw new IllegalStateException("provider " + NAME + " 已被移除，无法统计 init 次数");
            }
            CountingCipherProvider provider = (CountingCipherProvider) owner;
            this.initCount = provider.initCount;
            this.distinctKeys = provider.distinctKeys;
        }

        /**
         * 找一个<b>不是自己</b>的 {@code Cipher.AES/GCM/NoPadding} 实现当替身。
         */
        private static Cipher findDelegate() throws NoSuchAlgorithmException, NoSuchPaddingException {
            for (Provider provider : Security.getProviders()) {
                if (NAME.equals(provider.getName())) {
                    continue;
                }
                if (provider.getService("Cipher", TRANSFORMATION) != null) {
                    return Cipher.getInstance(TRANSFORMATION, provider);
                }
            }
            throw new NoSuchAlgorithmException("找不到 " + TRANSFORMATION + " 的替代实现");
        }

        @Override
        protected void engineSetMode(String mode) {
            // 替身本来就是按完整 transformation 建出来的，模式/填充已经设好 ⇒ 什么都不做
        }

        @Override
        protected void engineSetPadding(String padding) {
            // 同上
        }

        @Override
        protected int engineGetBlockSize() {
            return delegate.getBlockSize();
        }

        @Override
        protected int engineGetOutputSize(int inputLen) {
            return delegate.getOutputSize(inputLen);
        }

        @Override
        protected byte[] engineGetIV() {
            return delegate.getIV();
        }

        @Override
        protected AlgorithmParameters engineGetParameters() {
            return delegate.getParameters();
        }

        @Override
        protected void engineInit(int opmode, Key key, SecureRandom random)
                throws InvalidKeyException {
            initCount.incrementAndGet();
            distinctKeys.add(key);
            delegate.init(opmode, key, random);
        }

        @Override
        protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params, SecureRandom random)
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            initCount.incrementAndGet();
            distinctKeys.add(key);
            delegate.init(opmode, key, params, random);
        }

        @Override
        protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random)
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            initCount.incrementAndGet();
            distinctKeys.add(key);
            delegate.init(opmode, key, params, random);
        }

        @Override
        protected byte[] engineUpdate(byte[] input, int offset, int len) {
            return delegate.update(input, offset, len);
        }

        @Override
        protected int engineUpdate(byte[] input, int offset, int len, byte[] output, int outputOffset)
                throws ShortBufferException {
            return delegate.update(input, offset, len, output, outputOffset);
        }

        @Override
        protected byte[] engineDoFinal(byte[] input, int offset, int len)
                throws IllegalBlockSizeException, BadPaddingException {
            return delegate.doFinal(input, offset, len);
        }

        @Override
        protected int engineDoFinal(byte[] input, int offset, int len, byte[] output, int outputOffset)
                throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
            return delegate.doFinal(input, offset, len, output, outputOffset);
        }

        @Override
        protected void engineUpdateAAD(byte[] input, int offset, int len) {
            delegate.updateAAD(input, offset, len);
        }
    }
}
