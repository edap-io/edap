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

package io.edap.crypto.mac;

import io.edap.crypto.internal.InstancePool;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCE MAC 实现 —— JDK 内置 {@link Mac}。
 *
 * <p><b>ThreadLocal 复用</b>：{@code Mac.getInstance} 每次 1~3μs，
 * {@link Mac} 非线程安全 ⇒ provider 内部用 {@link InstancePool} 每线程复用一个实例。</p>
 *
 * <p><b>★ 实例必须被 session 独占</b>：{@code Mac.init(key)} 会重置内部状态。
 * 同一线程里两个活 session 共用一个实例时，后一个的 {@code init} 会把前一个已喂的
 * 数据清掉 ⇒ 前一个算出来的 MAC 是错的，而且不报错。所以每个 session 独占自己的
 * 实例，用完（{@code doFinal()}）再还回池子。</p>
 *
 * <p><b>★★ 同一把 key 不重复 init</b>（Solaris 上省掉每次 ~130μs）：</p>
 *
 * <p>Solaris 的 {@code HmacSHA256} 落在 {@code SunPKCS11-Solaris}（PKCS#11 provider），
 * key 必须<b>导进 token</b>。实测（build-01，Solaris 11.4 SPARC，Oracle JDK 17.0.19，
 * {@code io.edap.crypto.benchmark.CryptoCostProbe}）：</p>
 *
 * <table border="1">
 *   <caption>build-01 实测</caption>
 *   <tr><th>写法</th><th>ns/op</th></tr>
 *   <tr><td>{@code Mac.init(同一个 SecretKeySpec 对象)}</td><td>10 385</td></tr>
 *   <tr><td>{@code Mac.init(每次 new 一个 SecretKeySpec，字节相同)}</td><td><b>133 667</b></td></tr>
 * </table>
 *
 * <p>⇒ <b>JDK 的 token 导入缓存是按 key <i>对象身份</i>走的，跟字节无关</b>。
 * 早先的实现每次操作都 {@code new SecretKeySpec(...)} ⇒ <b>每次操作都重新导一次 key</b>，
 * 一次 HMAC 的固定成本从 ~12μs 涨到 ~136μs（比一次 OpenSSL HMAC 还贵 40 倍）。</p>
 *
 * <p>本实现的做法：池里的实例<b>记住自己是哪把 key init 的</b>（见 {@link KeyedMac}）——
 * 同一把 key 直接跳过 {@code init}，因为 {@code Mac.doFinal()} 之后实例已经回到
 * 「init 完、无待算数据」的状态（这正是 {@link MacSession#doFinal()} 契约里
 * 「key 保留」的含义）；换 key 时才 {@code init}。</p>
 */
public class JceMacProvider implements MacProvider {

    private final String algorithm;

    private final InstancePool<KeyedMac> pool;

    /**
     * 至今调用过几次 {@code Mac.init} —— <b>仅供测试</b>。
     *
     * <p><b>为什么要有这个计数器</b>：Solaris 上 {@code HmacSHA256} 落在
     * {@code SunPKCS11-Solaris}，每次 {@code init} 一个<b>新</b> key 对象都要把 key
     * 导进 PKCS#11 token（实测 ~130μs/次，比复用同一个 key 对象贵 12.9 倍）。
     * 这条代价在开发机上（SunJCE 软件路径，~40ns）<b>完全看不出来</b>
     * ⇒ 只能靠「init 了几次」这个<b>结构性事实</b>来守，时间断言必 flaky。</p>
     *
     * <p>计数点就落在 {@code init} 的<b>调用现场</b> ⇒ 任何让 init 次数变多的改动
     * 都会被它看见。</p>
     */
    private final AtomicInteger initCounter = new AtomicInteger();

    /**
     * 至今构造过几个 {@link SecretKeySpec} —— <b>仅供测试</b>。
     *
     * <p>JDK 的 token 导入缓存按 key <b>对象身份</b>走 ⇒ 同一把 key 必须是<b>同一个对象</b>
     * 才谈得上「复用」。这个计数器数的是「造了几个 key 对象」，
     * 与「传给 init 的是不是同一个对象」等价。</p>
     */
    private final AtomicInteger keySpecCounter = new AtomicInteger();

    /**
     * MAC 长度 —— 每个算法的常量，构造期取一次即可。
     */
    private final int macLength;

    public JceMacProvider(String algorithm) {
        if (algorithm == null || algorithm.isEmpty()) {
            throw new IllegalArgumentException("mac algorithm required");
        }
        this.algorithm = algorithm;
        this.pool = new InstancePool<>(() -> {
            try {
                return new KeyedMac(Mac.getInstance(algorithm));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("unsupported mac algorithm: " + algorithm, e);
            }
        });
        // 立刻建一个实例 —— 算法不支持时在构造期就失败，而不是等到第一次调用
        KeyedMac probe = pool.acquire();
        this.macLength = probe.mac.getMacLength();
        pool.release(probe);
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    /**
     * 至今调用过几次 {@code Mac.init} —— <b>仅供测试</b>（守「同一把 key 不重复 init」）。
     */
    int initCount() {
        return initCounter.get();
    }

    /**
     * 至今构造过几个 {@link SecretKeySpec} —— <b>仅供测试</b>。
     */
    int keySpecCount() {
        return keySpecCounter.get();
    }

    @Override
    public MacSession newSession(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("mac key required");
        }
        // 拷贝一份 —— 调用方可能复用/改写原数组
        return new JceMacSession(pool, algorithm, key.clone(), initCounter, keySpecCounter);
    }

    @Override
    public int getMacLength() {
        return macLength;
    }

    /**
     * 池里的实例 —— 除了 {@link Mac} 本身，还记住「当前是用哪把 key init 的」。
     *
     * <p><b>为什么需要记</b>：{@code init} 是「换 key + 重置状态」两件事合一，
     * 而换 key 在 Solaris 上意味着一次 ~130μs 的 token 导入。
     * 池里的实例只要没换过 key，它就还停在「init 完、无待算数据」的状态
     * ⇒ 直接拿来用即可，不必再 {@code init}。</p>
     */
    private static final class KeyedMac {

        private final Mac mac;

        /**
         * 当前已 {@code init} 的 key（字节副本）；{@code null} 表示还没 init 过。
         */
        private byte[] key;

        KeyedMac(Mac mac) {
            this.mac = mac;
        }

        /**
         * 这个实例是不是已经用 {@code candidate} init 过了。
         */
        boolean isKeyedWith(byte[] candidate) {
            return key != null && Arrays.equals(key, candidate);
        }
    }

    /**
     * JCE MAC 会话 —— 独占一个 {@link Mac} 实例。
     */
    static final class JceMacSession implements MacSession {

        private final InstancePool<KeyedMac> pool;

        private final String algorithm;

        private final byte[] key;

        /**
         * init 次数计数器（<b>仅供测试</b>）—— 与 provider 共用同一个。
         */
        private final AtomicInteger initCounter;

        /**
         * key 对象计数计数器（<b>仅供测试</b>）—— 与 provider 共用同一个。
         */
        private final AtomicInteger keySpecCounter;

        /**
         * 当前独占的实例；{@code null} 表示还没借（用完已归还）。
         */
        private KeyedMac keyed;

        JceMacSession(InstancePool<KeyedMac> pool, String algorithm, byte[] key,
                AtomicInteger initCounter, AtomicInteger keySpecCounter) {
            this.pool = pool;
            this.algorithm = algorithm;
            this.key = key;
            this.initCounter = initCounter;
            this.keySpecCounter = keySpecCounter;
        }

        private KeyedMac keyed() {
            if (keyed == null) {
                KeyedMac instance = pool.acquire();
                try {
                    if (!instance.isKeyedWith(key)) {
                        SecretKeySpec spec = new SecretKeySpec(key, algorithm);
                        keySpecCounter.incrementAndGet();
                        initCounter.incrementAndGet();
                        // init 同时完成「换 key」与「重置状态」
                        instance.mac.init(spec);
                        instance.key = key.clone();
                    }
                    // else：同一把 key —— 实例还停在「init 完、无待算数据」的状态，直接用
                } catch (InvalidKeyException e) {
                    // init 失败 ⇒ 实例状态不可知，标记成「没 key」，下次一定会重新 init
                    instance.key = null;
                    pool.release(instance);
                    throw new IllegalArgumentException("invalid mac key for " + algorithm, e);
                }
                keyed = instance;
            }
            return keyed;
        }

        @Override
        public void update(byte[] data, int offset, int len) {
            keyed().mac.update(data, offset, len);
        }

        @Override
        public byte[] doFinal() {
            KeyedMac instance = keyed();
            // Mac.doFinal() 之后自动回到刚 init 完的状态（key 保留）
            byte[] result = instance.mac.doFinal();
            pool.release(instance);
            keyed = null;
            return result;
        }

        @Override
        public void reset() {
            if (keyed != null) {
                keyed.mac.reset();
                pool.release(keyed);
                keyed = null;
            }
        }

        @Override
        public void close() {
            reset();
        }
    }
}
