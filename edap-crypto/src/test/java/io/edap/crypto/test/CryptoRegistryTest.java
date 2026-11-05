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
import io.edap.crypto.CryptoRegistry;
import io.edap.crypto.CryptoUnavailableException;
import io.edap.crypto.cipher.CipherProvider;
import io.edap.crypto.cipher.JceCipherProvider;
import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.DigestSession;
import io.edap.crypto.digest.JceDigestProvider;
import io.edap.crypto.digest.NativeDigestProvider;
import io.edap.crypto.internal.CryptoSwitches;
import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.NativeMacProvider;
import io.edap.crypto.random.JceRandomProvider;
import io.edap.crypto.random.RandomProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CryptoRegistry} 的注册 / 查找 / 覆盖 / 回退 / 非法参数 单测。
 */
public class CryptoRegistryTest {

    /**
     * 一个空的 digest provider —— 只用来验证「注册表认不认它」，不做实际计算。
     */
    static final class StubDigestProvider implements DigestProvider {

        @Override
        public String algorithm() {
            return "STUB";
        }

        @Override
        public DigestSession newSession() {
            throw new UnsupportedOperationException("stub");
        }
    }

    @BeforeEach
    public void setUp() {
        CryptoRegistry.resetForTest();
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(CryptoSwitches.PROP_GLOBAL_NATIVE);
        System.clearProperty(CryptoSwitches.PROP_STRICT);
        System.clearProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST));
        System.clearProperty(CryptoSwitches.providerProperty(CryptoKind.MAC));
        System.clearProperty(CryptoSwitches.nativeProperty(CryptoKind.DIGEST));
        CryptoRegistry.resetForTest();
    }

    // ------------------------------------------------------------------ 注册

    @Test
    @DisplayName("注册后能按名字查回来")
    public void testRegisterAndLookup() {
        DigestProvider provider = new StubDigestProvider();
        CryptoRegistry.registerDigest("STUB", provider);

        assertSame(provider, CryptoRegistry.digest("STUB"));
        assertTrue(CryptoRegistry.digestNames().contains("STUB"));
    }

    @Test
    @DisplayName("同名注册覆盖旧实现")
    public void testRegisterOverridesSameName() {
        DigestProvider first = new StubDigestProvider();
        DigestProvider second = new StubDigestProvider();
        CryptoRegistry.registerDigest("STUB", first);
        CryptoRegistry.registerDigest("STUB", second);

        assertSame(second, CryptoRegistry.digest("STUB"));
        assertNotSame(first, CryptoRegistry.digest("STUB"));
    }

    @Test
    @DisplayName("MAC / cipher / random 三类注册同样生效")
    public void testRegisterOtherKinds() {
        JceMacProvider mac = new JceMacProvider("HmacSHA256");
        JceCipherProvider cipher = JceCipherProvider.forTransformation("AES/GCM/NoPadding");
        JceRandomProvider random = new JceRandomProvider();

        CryptoRegistry.registerMac("HmacSHA256", mac);
        CryptoRegistry.registerCipher("AES/GCM/NoPadding", cipher);
        CryptoRegistry.registerRandom("default", random);

        assertSame(mac, CryptoRegistry.mac("HmacSHA256"));
        assertSame(cipher, CryptoRegistry.cipher("AES/GCM/NoPadding"));
        assertSame(random, CryptoRegistry.random("default"));

        assertTrue(CryptoRegistry.macNames().contains("HmacSHA256"));
        assertTrue(CryptoRegistry.cipherNames().contains("AES/GCM/NoPadding"));
        assertTrue(CryptoRegistry.randomNames().contains("default"));
    }

    @Test
    @DisplayName("名字为 null / 空 ⇒ IllegalArgumentException")
    public void testRegisterRejectsBadName() {
        assertThrows(IllegalArgumentException.class,
                () -> CryptoRegistry.registerDigest(null, new StubDigestProvider()));
        assertThrows(IllegalArgumentException.class,
                () -> CryptoRegistry.registerDigest("", new StubDigestProvider()));
        assertThrows(IllegalArgumentException.class,
                () -> CryptoRegistry.registerMac(null, new JceMacProvider("HmacSHA256")));
        assertThrows(IllegalArgumentException.class,
                () -> CryptoRegistry.registerCipher("", JceCipherProvider.forTransformation("AES/GCM/NoPadding")));
        assertThrows(IllegalArgumentException.class,
                () -> CryptoRegistry.registerRandom(null, new JceRandomProvider()));
    }

    @Test
    @DisplayName("provider 为 null ⇒ IllegalArgumentException")
    public void testRegisterRejectsNullProvider() {
        assertThrows(IllegalArgumentException.class, () -> CryptoRegistry.registerDigest("X", null));
        assertThrows(IllegalArgumentException.class, () -> CryptoRegistry.registerMac("X", null));
        assertThrows(IllegalArgumentException.class, () -> CryptoRegistry.registerCipher("X", null));
        assertThrows(IllegalArgumentException.class, () -> CryptoRegistry.registerRandom("X", null));
    }

    @Test
    @DisplayName("查找时名字为 null / 空 ⇒ IllegalArgumentException")
    public void testLookupRejectsBadName() {
        assertThrows(IllegalArgumentException.class, () -> CryptoRegistry.digest(null));
        assertThrows(IllegalArgumentException.class, () -> CryptoRegistry.digest(""));
        assertThrows(IllegalArgumentException.class, () -> CryptoRegistry.mac(""));
        assertThrows(IllegalArgumentException.class, () -> CryptoRegistry.cipher(""));
        assertThrows(IllegalArgumentException.class, () -> CryptoRegistry.random(""));
    }

    // ------------------------------------------------------------------ 回退

    @Test
    @DisplayName("未注册的 digest：强制 jce ⇒ JCE；不强制 ⇒ native 可用则 native")
    public void testUnregisteredDigestResolvesBySwitch() {
        // 显式强制 JCE —— 必须拿到 JCE 实现
        System.setProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST), "jce");
        CryptoRegistry.resetForTest();
        DigestProvider forced = CryptoRegistry.digest("SHA-256");
        assertInstanceOf(JceDigestProvider.class, forced);
        assertEquals("SHA-256", forced.algorithm());

        // 不强制 —— native 可用则 native，否则降级 JCE（两种都要能拿到可用的实现）
        System.clearProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST));
        CryptoRegistry.resetForTest();
        DigestProvider resolved = CryptoRegistry.digest("SHA-256");
        assertEquals("SHA-256", resolved.algorithm());
        if (NativeDigestProvider.tryCreate("SHA-256") != null) {
            assertInstanceOf(NativeDigestProvider.class, resolved, "native 可用时应选 native");
        } else {
            assertInstanceOf(JceDigestProvider.class, resolved, "native 不可用时应降级 JCE");
        }
    }

    @Test
    @DisplayName("未注册的 MAC：强制 jce ⇒ JCE；不强制 ⇒ native 可用则 native")
    public void testUnregisteredMacResolvesBySwitch() {
        System.setProperty(CryptoSwitches.providerProperty(CryptoKind.MAC), "jce");
        CryptoRegistry.resetForTest();
        assertInstanceOf(JceMacProvider.class, CryptoRegistry.mac("HmacSHA256"));

        System.clearProperty(CryptoSwitches.providerProperty(CryptoKind.MAC));
        CryptoRegistry.resetForTest();
        MacProvider resolved = CryptoRegistry.mac("HmacSHA256");
        if (NativeMacProvider.tryCreate("HmacSHA256") != null) {
            assertInstanceOf(NativeMacProvider.class, resolved, "native 可用时应选 native");
        } else {
            assertInstanceOf(JceMacProvider.class, resolved, "native 不可用时应降级 JCE");
        }
    }

    @Test
    @DisplayName("未注册的 cipher 回退到 JCE")
    public void testUnregisteredCipherFallsBackToJce() {
        CipherProvider provider = CryptoRegistry.cipher("AES/GCM/NoPadding");
        assertInstanceOf(JceCipherProvider.class, provider);
    }

    @Test
    @DisplayName("未注册的 random 回退到 JCE")
    public void testUnregisteredRandomFallsBackToJce() {
        RandomProvider provider = CryptoRegistry.random("default");
        assertInstanceOf(JceRandomProvider.class, provider);
    }

    @Test
    @DisplayName("默认 provider 只解析一次并缓存")
    public void testDefaultProviderIsCached() {
        DigestProvider first = CryptoRegistry.digest("SHA-256");
        DigestProvider second = CryptoRegistry.digest("SHA-256");
        assertSame(first, second);
    }

    // ------------------------------------------------------------------ 开关

    @Test
    @DisplayName("总闸关掉 ⇒ 全部走 JCE")
    public void testGlobalNativeOffForcesJce() {
        System.setProperty(CryptoSwitches.PROP_GLOBAL_NATIVE, "false");
        CryptoRegistry.resetForTest();

        assertInstanceOf(JceDigestProvider.class, CryptoRegistry.digest("SHA-256"));
        assertInstanceOf(JceMacProvider.class, CryptoRegistry.mac("HmacSHA256"));
    }

    @Test
    @DisplayName("强制 jce ⇒ 走 JCE")
    public void testForcedJce() {
        System.setProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST), "jce");
        CryptoRegistry.resetForTest();

        assertInstanceOf(JceDigestProvider.class, CryptoRegistry.digest("SHA-256"));
    }

    @Test
    @DisplayName("强制 native 但算法无 native 实现 ⇒ CryptoUnavailableException")
    public void testForcedNativeThrowsWhenUnavailable() {
        // SHA-512 刻意不在 native 支持列表里 —— 无论 native 库是否在 classpath 上都不可用
        System.setProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST), "native");
        CryptoRegistry.resetForTest();

        assertThrows(CryptoUnavailableException.class, () -> CryptoRegistry.digest("SHA-512"));
    }

    @Test
    @DisplayName("严格模式 ⇒ native 不可用时抛异常而不是静默降级")
    public void testStrictModeThrows() {
        System.setProperty(CryptoSwitches.PROP_STRICT, "true");
        CryptoRegistry.resetForTest();

        assertThrows(CryptoUnavailableException.class, () -> CryptoRegistry.digest("SHA-512"));
    }

    // ------------------------------------------------------------------ 其它

    @Test
    @DisplayName("resetForTest 清空显式注册与默认缓存")
    public void testResetForTest() {
        CryptoRegistry.registerDigest("STUB", new StubDigestProvider());
        assertTrue(CryptoRegistry.digestNames().contains("STUB"));

        CryptoRegistry.resetForTest();

        // 显式注册的被清掉了
        assertFalse(CryptoRegistry.digestNames().contains("STUB"));
        // SPI 自动发现的 provider 仍在（回到 bootstrap 状态）
        assertTrue(CryptoRegistry.digestNames().contains(
                io.edap.crypto.test.spi.TestCryptoProviderFactory.ALGORITHM));
        assertTrue(CryptoRegistry.macNames().isEmpty());
        assertTrue(CryptoRegistry.cipherNames().isEmpty());
        assertTrue(CryptoRegistry.randomNames().isEmpty());
    }

    @Test
    @DisplayName("describe 给出四个注册表的快照")
    public void testDescribe() {
        CryptoRegistry.registerDigest("STUB", new StubDigestProvider());
        String text = String.join(";", CryptoRegistry.describe());
        assertTrue(text.contains("digest="), text);
        assertTrue(text.contains("STUB"), text);
    }
}
