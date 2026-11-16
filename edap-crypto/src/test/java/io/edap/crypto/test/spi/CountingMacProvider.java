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

import javax.crypto.Mac;
import javax.crypto.MacSpi;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 能数 {@code init} 次数的 {@link Mac} —— 用来守住「同一把 key 不该重复 init」
 * 这条<b>结构性</b>性能不变量。
 *
 * <p><b>为什么不能靠测时间</b>：Solaris 上 {@code HmacSHA256} 落在
 * {@code SunPKCS11-Solaris}，每次 {@code Mac.init(new SecretKeySpec(...))} 都要
 * 把 key 导进 token ⇒ 实测 ~130μs；而开发机（SunJCE）上这条路径只要 ~40ns。
 * <b>同一个缺陷在两台机器上的时间差是 3000 倍</b> ⇒ 时间断言要么在开发机上
 * 永远绿、要么在 Solaris 上永远 flaky。能跨平台守住它的只有「init 被调了几次」
 * 这个结构性事实。</p>
 *
 * <p><b>做法</b>：往 JCE provider 列表<b>头部</b>插一个自己的 provider，
 * {@code Mac.getInstance("HmacSHA256")} 就会拿到它；它把真正的计算转交给
 * 「另一个」真实 {@link Mac}，自己只数 {@code engineInit} 的次数。</p>
 *
 * <p><b>实现细节</b>：{@code Provider.Service} 在 JDK 17 上只认<b>无参构造</b>
 * （{@code setConstructorParameter} 不存在），所以替身与计数器只能由 SPI
 * 自己去 {@link Security} 里找 —— 按 provider 名字把自己排除掉即可。</p>
 */
public final class CountingMacProvider extends Provider {

    /**
     * provider 名字（{@link Security#removeProvider(String)} 用它摘掉）。
     */
    public static final String NAME = "EdapCountingMac";

    /**
     * 被统计的 {@code engineInit} 次数。
     */
    private final AtomicInteger initCount = new AtomicInteger();

    public CountingMacProvider() {
        super(NAME, "1.0", "数 init 次数的 Mac（测试用）");
        putService(new Service(this, "Mac", "HmacSHA256",
                CountingMacSpi.class.getName(), null, null));
    }

    /**
     * 至今被 {@code init} 了几次。
     */
    public int initCount() {
        return initCount.get();
    }

    /**
     * 真正的 {@link MacSpi} —— 只加了一层计数，其余全部转交。
     */
    public static final class CountingMacSpi extends MacSpi {

        private final Mac delegate;

        private final AtomicInteger initCount;

        public CountingMacSpi() throws NoSuchAlgorithmException {
            this.delegate = findDelegate();
            Provider owner = Security.getProvider(NAME);
            if (owner == null) {
                throw new IllegalStateException("provider " + NAME + " 已被移除，无法统计 init 次数");
            }
            this.initCount = ((CountingMacProvider) owner).initCount;
        }

        /**
         * 找一个<b>不是自己</b>的 {@code Mac.HmacSHA256} 实现当替身。
         */
        private static Mac findDelegate() throws NoSuchAlgorithmException {
            for (Provider provider : Security.getProviders()) {
                if (NAME.equals(provider.getName())) {
                    continue;
                }
                if (provider.getService("Mac", "HmacSHA256") != null) {
                    return Mac.getInstance("HmacSHA256", provider);
                }
            }
            throw new NoSuchAlgorithmException("找不到 HmacSHA256 的替代实现");
        }

        @Override
        protected int engineGetMacLength() {
            return delegate.getMacLength();
        }

        @Override
        protected void engineInit(Key key, AlgorithmParameterSpec params)
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            initCount.incrementAndGet();
            delegate.init(key, params);
        }

        @Override
        protected void engineUpdate(byte input) {
            delegate.update(input);
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int len) {
            delegate.update(input, offset, len);
        }

        @Override
        protected byte[] engineDoFinal() {
            return delegate.doFinal();
        }

        @Override
        protected void engineReset() {
            delegate.reset();
        }
    }
}
