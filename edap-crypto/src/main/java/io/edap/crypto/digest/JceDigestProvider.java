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

package io.edap.crypto.digest;

import io.edap.crypto.internal.InstancePool;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * JCE 摘要实现 —— JDK 内置 {@link MessageDigest}。
 *
 * <p><b>ThreadLocal 复用</b>：{@code MessageDigest.getInstance} 每次调用 1~3μs
 * （要查 provider、创建 spi），而 {@code MessageDigest} 本身<b>非</b>线程安全。
 * 所以 provider 是全局单例语义，内部用 {@link InstancePool} 每线程复用一个实例，
 * 真正与线程绑定的单位是 {@link DigestSession}。</p>
 *
 * <p><b>★ 实例必须被 session 独占</b>：{@code MessageDigest.reset()} 会清掉已喂的数据。
 * 同一线程里两个活 session 共用一个实例时，后一个的 {@code reset()} 会把前一个的数据
 * 清掉 ⇒ 前一个算出来的摘要是错的，而且不报错。所以每个 session 独占自己的实例，
 * 用完（{@code digest()}）再还回池子。</p>
 */
public class JceDigestProvider implements DigestProvider {

    private final String algorithm;

    private final InstancePool<MessageDigest> pool;

    /**
     * 摘要长度 —— 每个算法的常量，构造期取一次即可。
     */
    private final int digestLength;

    public JceDigestProvider(String algorithm) {
        if (algorithm == null || algorithm.isEmpty()) {
            throw new IllegalArgumentException("digest algorithm required");
        }
        this.algorithm = algorithm;
        this.pool = new InstancePool<>(() -> {
            try {
                return MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("unsupported digest algorithm: " + algorithm, e);
            }
        });
        // 立刻建一个实例 —— 算法不支持时在构造期就失败，而不是等到第一次调用
        MessageDigest probe = pool.acquire();
        this.digestLength = probe.getDigestLength();
        pool.release(probe);
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public DigestSession newSession() {
        return new JceDigestSession(pool);
    }

    @Override
    public int getDigestLength() {
        return digestLength;
    }

    /**
     * JCE 摘要会话 —— 独占一个 {@link MessageDigest} 实例。
     */
    static final class JceDigestSession implements DigestSession {

        private final InstancePool<MessageDigest> pool;

        /**
         * 当前独占的实例；{@code null} 表示还没借（用完已归还）。
         */
        private MessageDigest digest;

        JceDigestSession(InstancePool<MessageDigest> pool) {
            this.pool = pool;
        }

        private MessageDigest instance() {
            if (digest == null) {
                digest = pool.acquire();
                digest.reset();
            }
            return digest;
        }

        @Override
        public void update(byte[] data, int offset, int len) {
            instance().update(data, offset, len);
        }

        @Override
        public byte[] digest() {
            MessageDigest md = instance();
            // MessageDigest.digest() 之后自动回到初始状态
            byte[] result = md.digest();
            // 算完了就归还 —— 下次 session 直接复用
            pool.release(md);
            digest = null;
            return result;
        }

        @Override
        public void reset() {
            if (digest != null) {
                digest.reset();
                pool.release(digest);
                digest = null;
            }
        }

        @Override
        public void close() {
            reset();
        }
    }
}
