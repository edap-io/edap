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
 */
public class JceMacProvider implements MacProvider {

    private final String algorithm;

    private final InstancePool<Mac> pool;

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
                return Mac.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("unsupported mac algorithm: " + algorithm, e);
            }
        });
        // 立刻建一个实例 —— 算法不支持时在构造期就失败，而不是等到第一次调用
        Mac probe = pool.acquire();
        this.macLength = probe.getMacLength();
        pool.release(probe);
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public MacSession newSession(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("mac key required");
        }
        // 拷贝一份 —— 调用方可能复用/改写原数组
        return new JceMacSession(pool, algorithm, key.clone());
    }

    @Override
    public int getMacLength() {
        return macLength;
    }

    /**
     * JCE MAC 会话 —— 独占一个 {@link Mac} 实例。
     */
    static final class JceMacSession implements MacSession {

        private final InstancePool<Mac> pool;

        private final String algorithm;

        private final byte[] key;

        /**
         * 当前独占的实例；{@code null} 表示还没借（用完已归还）。
         */
        private Mac mac;

        JceMacSession(InstancePool<Mac> pool, String algorithm, byte[] key) {
            this.pool = pool;
            this.algorithm = algorithm;
            this.key = key;
        }

        private Mac mac() {
            if (mac == null) {
                Mac instance = pool.acquire();
                try {
                    // init 同时完成「换 key」与「重置状态」
                    instance.init(new SecretKeySpec(key, algorithm));
                } catch (InvalidKeyException e) {
                    pool.release(instance);
                    throw new IllegalArgumentException("invalid mac key for " + algorithm, e);
                }
                mac = instance;
            }
            return mac;
        }

        @Override
        public void update(byte[] data, int offset, int len) {
            mac().update(data, offset, len);
        }

        @Override
        public byte[] doFinal() {
            Mac instance = mac();
            // Mac.doFinal() 之后自动回到刚 init 完的状态（key 保留）
            byte[] result = instance.doFinal();
            pool.release(instance);
            mac = null;
            return result;
        }

        @Override
        public void reset() {
            if (mac != null) {
                mac.reset();
                pool.release(mac);
                mac = null;
            }
        }

        @Override
        public void close() {
            reset();
        }
    }
}
