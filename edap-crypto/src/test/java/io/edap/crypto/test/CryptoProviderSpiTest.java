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

package io.edap.crypto.test;

import io.edap.crypto.CryptoKind;
import io.edap.crypto.CryptoProvider;
import io.edap.crypto.CryptoProviderFactory;
import io.edap.crypto.CryptoRegistry;
import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.test.spi.TestCryptoProviderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ServiceLoader 自动发现通道（注册通道之二）。
 */
public class CryptoProviderSpiTest {

    @BeforeEach
    public void setUp() {
        CryptoRegistry.resetForTest();
    }

    @AfterEach
    public void tearDown() {
        CryptoRegistry.resetForTest();
    }

    @Test
    @DisplayName("ServiceLoader 能发现测试用的 CryptoProviderFactory")
    public void testServiceLoaderFindsFactory() {
        boolean found = false;
        for (CryptoProviderFactory factory : ServiceLoader.load(CryptoProviderFactory.class)) {
            if (factory instanceof TestCryptoProviderFactory) {
                found = true;
                assertEquals(CryptoKind.DIGEST, factory.kind());
                assertEquals(TestCryptoProviderFactory.ALGORITHM, factory.algorithm());
                CryptoProvider provider = factory.create();
                assertInstanceOf(DigestProvider.class, provider);
                assertEquals(TestCryptoProviderFactory.ALGORITHM, provider.algorithm());
            }
        }
        assertTrue(found, "ServiceLoader 没有发现 TestCryptoProviderFactory");
    }

    @Test
    @DisplayName("注册表自动注册了 SPI 发现的 provider")
    public void testRegistryAutoRegistersSpiProvider() {
        assertTrue(CryptoRegistry.digestNames().contains(TestCryptoProviderFactory.ALGORITHM),
                "SPI provider 没有被自动注册，digestNames=" + CryptoRegistry.digestNames());

        DigestProvider provider = CryptoRegistry.digest(TestCryptoProviderFactory.ALGORITHM);
        assertInstanceOf(TestCryptoProviderFactory.StubDigestProvider.class, provider);
    }

    @Test
    @DisplayName("resetForTest 之后 SPI provider 仍在（回到 bootstrap 状态）")
    public void testSpiProviderSurvivesReset() {
        DigestProvider before = CryptoRegistry.digest(TestCryptoProviderFactory.ALGORITHM);
        CryptoRegistry.resetForTest();
        DigestProvider after = CryptoRegistry.digest(TestCryptoProviderFactory.ALGORITHM);

        assertInstanceOf(TestCryptoProviderFactory.StubDigestProvider.class, after);
        // 每次发现都新建实例 —— 这里只断言类型一致，不断言是同一个对象
        assertSame(before.getClass(), after.getClass());
    }
}
