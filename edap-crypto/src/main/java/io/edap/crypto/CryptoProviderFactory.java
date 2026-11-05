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

package io.edap.crypto;

/**
 * {@link CryptoProvider} 的工厂 —— {@code ServiceLoader} SPI。
 *
 * <p>用法：在 {@code META-INF/services/io.edap.crypto.CryptoProviderFactory} 里登记
 * 实现类，{@link CryptoRegistry} 初始化时会自动发现并注册。</p>
 *
 * <p>这是与「显式调 {@code CryptoRegistry.registerXxx}」并列的第二条注册通道，
 * 适合把第三方实现（BouncyCastle、用户自研 JNI 库）插进来而不改调用方代码。</p>
 */
public interface CryptoProviderFactory {

    /**
     * 原语类别。
     */
    CryptoKind kind();

    /**
     * 算法名（与 {@link CryptoProvider#algorithm()} 一致）。
     */
    String algorithm();

    /**
     * 创建 provider 实例。
     */
    CryptoProvider create();
}
