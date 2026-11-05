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

package io.edap.crypto.random;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * JCE 随机数实现 —— JDK 内置 {@link SecureRandom}。
 *
 * <p><b>ThreadLocal 的原因</b>：JDK 默认的 {@code NativePRNG} 引擎在
 * {@code nextBytes} 上带引擎级 {@code synchronized} —— 多线程共用一个
 * {@link SecureRandom} 实例会串行化。每线程一个实例可以绕开这把引擎锁。</p>
 *
 * <p><b>为什么不做 native</b>：JDK 的 {@code SecureRandom} 默认就是 OS 原生熵源
 * （{@code NativePRNG} 走 {@code /dev/urandom}），JNI {@code RAND_bytes} 收益 ≈ 0、
 * 风险大 ⇒ random 不做 native 路线。</p>
 */
public class JceRandomProvider implements RandomProvider {

    /**
     * 默认引擎名 —— JDK 内部实现（通常 {@code NativePRNG}）。
     */
    public static final String DEFAULT_ENGINE = "default";

    /**
     * JDK 9+ 的 DRBG（{@code SHA256PRNG} 的 NIST DRBG 实现）。
     */
    public static final String DRBG_ENGINE = "DRBG";

    private final String engine;

    private final ThreadLocal<SecureRandom> randoms;

    /**
     * 用默认引擎。
     */
    public JceRandomProvider() {
        this(DEFAULT_ENGINE);
    }

    /**
     * @param engine {@link #DEFAULT_ENGINE} / {@link #DRBG_ENGINE} /
     *               其它 JCE {@code SecureRandom} 引擎名
     */
    public JceRandomProvider(String engine) {
        if (engine == null || engine.isEmpty()) {
            throw new IllegalArgumentException("random engine required");
        }
        this.engine = engine;
        this.randoms = ThreadLocal.withInitial(() -> {
            try {
                if (DEFAULT_ENGINE.equals(engine)) {
                    return new SecureRandom();
                }
                return SecureRandom.getInstance(engine);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("unsupported random engine: " + engine, e);
            }
        });
        randoms.get();
    }

    @Override
    public String algorithm() {
        return engine;
    }

    @Override
    public void nextBytes(byte[] buf) {
        randoms.get().nextBytes(buf);
    }

    @Override
    public void nextBytes(byte[] buf, int offset, int len) {
        if (buf == null) {
            throw new IllegalArgumentException("buffer required");
        }
        if (offset < 0 || len < 0 || offset + len > buf.length) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", len=" + len
                    + ", buf.length=" + buf.length);
        }
        if (len == 0) {
            return;
        }
        // SecureRandom 没有 offset/len 重载 —— 只能先填临时数组再拷回。
        byte[] tmp = new byte[len];
        randoms.get().nextBytes(tmp);
        System.arraycopy(tmp, 0, buf, offset, len);
    }
}
