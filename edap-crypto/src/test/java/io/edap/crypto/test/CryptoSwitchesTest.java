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
import io.edap.crypto.internal.CryptoDiagnostics;
import io.edap.crypto.internal.CryptoSwitches;
import io.edap.crypto.test.spi.CapturingWarningSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CryptoSwitches} 的三段式开关与「一次 WARN」契约。
 *
 * <p>告警断言走 {@link CryptoDiagnostics} 的 sink —— edap-crypto 刻意不依赖
 * edap-log-api（否则会形成跨模块类初始化环，见 {@link CryptoDiagnostics}）。</p>
 */
public class CryptoSwitchesTest {

    private CapturingWarningSink sink;

    @BeforeEach
    public void setUp() {
        sink = new CapturingWarningSink();
        CryptoDiagnostics.setSink(sink);
        sink.clear();
        CryptoSwitches.resetWarningsForTest();
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(CryptoSwitches.PROP_GLOBAL_NATIVE);
        System.clearProperty(CryptoSwitches.PROP_STRICT);
        for (CryptoKind kind : CryptoKind.values()) {
            System.clearProperty(CryptoSwitches.nativeProperty(kind));
            System.clearProperty(CryptoSwitches.providerProperty(kind));
        }
        CryptoSwitches.resetWarningsForTest();
        CryptoDiagnostics.resetSinkForTest();
    }

    // ------------------------------------------------------------------ 开关

    @Test
    @DisplayName("默认：native 允许、strict 关闭")
    public void testDefaults() {
        assertTrue(CryptoSwitches.nativeEnabled(CryptoKind.DIGEST));
        assertTrue(CryptoSwitches.nativeEnabled(CryptoKind.MAC));
        assertTrue(CryptoSwitches.nativeEnabled(CryptoKind.CIPHER));
        assertTrue(CryptoSwitches.nativeEnabled(CryptoKind.RANDOM));
        assertFalse(CryptoSwitches.isStrict());
        assertNull(CryptoSwitches.forcedProvider(CryptoKind.DIGEST));
    }

    @Test
    @DisplayName("总闸关闭 ⇒ 所有分项都关闭")
    public void testGlobalSwitchWins() {
        System.setProperty(CryptoSwitches.PROP_GLOBAL_NATIVE, "false");
        System.setProperty(CryptoSwitches.nativeProperty(CryptoKind.DIGEST), "true");

        assertFalse(CryptoSwitches.nativeEnabled(CryptoKind.DIGEST));
        assertFalse(CryptoSwitches.nativeEnabled(CryptoKind.MAC));
    }

    @Test
    @DisplayName("分项开关只影响自己")
    public void testPerKindSwitch() {
        System.setProperty(CryptoSwitches.nativeProperty(CryptoKind.MAC), "false");

        assertFalse(CryptoSwitches.nativeEnabled(CryptoKind.MAC));
        assertTrue(CryptoSwitches.nativeEnabled(CryptoKind.DIGEST));
    }

    @Test
    @DisplayName("属性名符合 edap.crypto.<kind>.native / .provider 约定")
    public void testPropertyNames() {
        assertEquals("edap.crypto.digest.native", CryptoSwitches.nativeProperty(CryptoKind.DIGEST));
        assertEquals("edap.crypto.mac.native", CryptoSwitches.nativeProperty(CryptoKind.MAC));
        assertEquals("edap.crypto.cipher.native", CryptoSwitches.nativeProperty(CryptoKind.CIPHER));
        assertEquals("edap.crypto.random.native", CryptoSwitches.nativeProperty(CryptoKind.RANDOM));
        assertEquals("edap.crypto.digest.provider", CryptoSwitches.providerProperty(CryptoKind.DIGEST));
        assertEquals("edap.crypto.mac.provider", CryptoSwitches.providerProperty(CryptoKind.MAC));
    }

    @Test
    @DisplayName("forcedProvider 认 native / jce（大小写不敏感），其它值当未设置")
    public void testForcedProvider() {
        System.setProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST), "native");
        assertEquals("native", CryptoSwitches.forcedProvider(CryptoKind.DIGEST));

        System.setProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST), "JCE");
        assertEquals("jce", CryptoSwitches.forcedProvider(CryptoKind.DIGEST));

        System.setProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST), "  native  ");
        assertEquals("native", CryptoSwitches.forcedProvider(CryptoKind.DIGEST));

        System.setProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST), "openssl");
        assertNull(CryptoSwitches.forcedProvider(CryptoKind.DIGEST));

        System.setProperty(CryptoSwitches.providerProperty(CryptoKind.DIGEST), "");
        assertNull(CryptoSwitches.forcedProvider(CryptoKind.DIGEST));
    }

    @Test
    @DisplayName("strict 开关")
    public void testStrict() {
        System.setProperty(CryptoSwitches.PROP_STRICT, "true");
        assertTrue(CryptoSwitches.isStrict());

        System.setProperty(CryptoSwitches.PROP_STRICT, "false");
        assertFalse(CryptoSwitches.isStrict());
    }

    @Test
    @DisplayName("bool 解析：true/false/on/off/yes/no/enable/disable")
    public void testBoolParsing() {
        assertTrue(CryptoSwitches.bool("true", false));
        assertTrue(CryptoSwitches.bool("TRUE", false));
        assertTrue(CryptoSwitches.bool("on", false));
        assertTrue(CryptoSwitches.bool("Yes", false));
        assertTrue(CryptoSwitches.bool("enable", false));
        assertTrue(CryptoSwitches.bool("enabled", false));

        assertFalse(CryptoSwitches.bool("false", true));
        assertFalse(CryptoSwitches.bool("off", true));
        assertFalse(CryptoSwitches.bool("no", true));
        assertFalse(CryptoSwitches.bool("disable", true));
        assertFalse(CryptoSwitches.bool("disabled", true));

        // 无法识别 ⇒ 用默认值
        assertTrue(CryptoSwitches.bool("???", true));
        assertFalse(CryptoSwitches.bool("???", false));

        // null / 空 ⇒ 用默认值
        assertTrue(CryptoSwitches.bool(null, true));
        assertFalse(CryptoSwitches.bool(null, false));
        assertTrue(CryptoSwitches.bool("   ", true));
    }

    // ------------------------------------------------------------------ WARN 去重

    @Test
    @DisplayName("同一个 key 只告警一次")
    public void testWarnOnceDeduplicates() {
        CryptoSwitches.warnOnce("k1", "native digest unavailable");
        CryptoSwitches.warnOnce("k1", "native digest unavailable");
        CryptoSwitches.warnOnce("k1", "native digest unavailable");

        assertEquals(1, sink.count());
        assertTrue(sink.contains("native digest unavailable"));
    }

    @Test
    @DisplayName("不同 key 各自告警一次")
    public void testWarnOncePerKey() {
        CryptoSwitches.warnOnce("k1", "msg-1");
        CryptoSwitches.warnOnce("k2", "msg-2");
        CryptoSwitches.warnOnce("k1", "msg-1");

        assertEquals(2, sink.count());
    }

    @Test
    @DisplayName("resetWarningsForTest 后可以重新告警")
    public void testResetWarningsAllowsRewarn() {
        CryptoSwitches.warnOnce("k1", "msg");
        assertEquals(1, sink.count());

        CryptoSwitches.resetWarningsForTest();
        CryptoSwitches.warnOnce("k1", "msg");
        assertEquals(2, sink.count());
    }
}
