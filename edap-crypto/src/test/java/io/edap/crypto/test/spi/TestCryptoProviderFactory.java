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

import io.edap.crypto.CryptoKind;
import io.edap.crypto.CryptoProvider;
import io.edap.crypto.CryptoProviderFactory;
import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.DigestSession;

/**
 * 测试用的 {@link CryptoProviderFactory} —— 验证 ServiceLoader 自动发现通道。
 *
 * <p>通过 {@code META-INF/services/io.edap.crypto.CryptoProviderFactory} 登记，
 * {@link io.edap.crypto.CryptoRegistry} 初始化时会自动把它注册进去。</p>
 */
public class TestCryptoProviderFactory implements CryptoProviderFactory {

    /**
     * 这个 SPI provider 注册的算法名 —— 与真实算法刻意不同，避免与 JCE 默认撞名。
     */
    public static final String ALGORITHM = "SPI-DIGEST";

    @Override
    public CryptoKind kind() {
        return CryptoKind.DIGEST;
    }

    @Override
    public String algorithm() {
        return ALGORITHM;
    }

    @Override
    public CryptoProvider create() {
        return new StubDigestProvider();
    }

    /**
     * 只用来验证「被注册上了」的桩实现。
     */
    public static class StubDigestProvider implements DigestProvider {

        @Override
        public String algorithm() {
            return ALGORITHM;
        }

        @Override
        public DigestSession newSession() {
            throw new UnsupportedOperationException("stub provider: 不做实际计算");
        }
    }
}
