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

import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.MacSession;
import io.edap.crypto.mac.NativeMacProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * native MAC vs JCE MAC —— 逐字节一致。
 *
 * <p>edap-native 在 edap-crypto 是 test scope，native 不可用时整类跳过。</p>
 */
public class JceVsNativeMacTest {

    private static NativeMacProvider nativeProvider(String algorithm) {
        return NativeMacProvider.tryCreate(algorithm);
    }

    @Test
    public void testHmacSha256NativeEqualsJce() {
        NativeMacProvider nativeProvider = nativeProvider("HmacSHA256");
        if (nativeProvider == null) {
            System.out.println("SKIP: native mac unavailable");
            return;
        }
        JceMacProvider jceProvider = new JceMacProvider("HmacSHA256");
        byte[] key = "this-is-a-test-signing-key-32-bytes!".getBytes(StandardCharsets.UTF_8);
        byte[] data = "hello edap crypto".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(mac(jceProvider, key, data), mac(nativeProvider, key, data));
    }

    @Test
    public void testHmacSha1NativeEqualsJce() {
        NativeMacProvider nativeProvider = nativeProvider("HmacSHA1");
        if (nativeProvider == null) {
            System.out.println("SKIP: native mac unavailable");
            return;
        }
        JceMacProvider jceProvider = new JceMacProvider("HmacSHA1");
        byte[] key = "this-is-a-test-signing-key-32-bytes!".getBytes(StandardCharsets.UTF_8);
        byte[] data = "hello edap crypto".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(mac(jceProvider, key, data), mac(nativeProvider, key, data));
    }

    /**
     * key 长短三档 —— RFC 2104 的两种预处理分支。
     */
    @Test
    public void testKeyLengthBranches() {
        NativeMacProvider nativeProvider = nativeProvider("HmacSHA256");
        if (nativeProvider == null) {
            System.out.println("SKIP: native mac unavailable");
            return;
        }
        JceMacProvider jceProvider = new JceMacProvider("HmacSHA256");
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        byte[][] keys = new byte[][]{
                new byte[]{0x01},
                "0123456789012345678901234567890123456789012345678901234567890123".getBytes(StandardCharsets.UTF_8),
                "0123456789012345678901234567890123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8)
        };
        for (byte[] key : keys) {
            assertArrayEquals(mac(jceProvider, key, data), mac(nativeProvider, key, data),
                    "key len=" + key.length);
        }
    }

    /**
     * 分片喂入与一次性喂入同结果。
     */
    @Test
    public void testChunkedUpdateEqualsOneShot() {
        NativeMacProvider nativeProvider = nativeProvider("HmacSHA256");
        if (nativeProvider == null) {
            System.out.println("SKIP: native mac unavailable");
            return;
        }
        JceMacProvider jceProvider = new JceMacProvider("HmacSHA256");

        byte[] key = "key-material-for-chunked-test".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[1000];
        new Random(43L).nextBytes(data);

        MacSession nativeSession = nativeProvider.newSession(key);
        MacSession jceSession = jceProvider.newSession(key);
        int chunk = 137;
        for (int off = 0; off < data.length; off += chunk) {
            int len = Math.min(chunk, data.length - off);
            nativeSession.update(data, off, len);
            jceSession.update(data, off, len);
        }
        assertArrayEquals(jceSession.doFinal(), nativeSession.doFinal());
    }

    @Test
    public void testRandomPayloadsMatch() {
        NativeMacProvider nativeProvider = nativeProvider("HmacSHA256");
        if (nativeProvider == null) {
            System.out.println("SKIP: native mac unavailable");
            return;
        }
        JceMacProvider jceProvider = new JceMacProvider("HmacSHA256");
        Random random = new Random(20231105L);
        byte[] key = new byte[37];
        random.nextBytes(key);
        int[] lengths = {0, 1, 63, 64, 65, 1024};
        for (int len : lengths) {
            byte[] data = new byte[len];
            random.nextBytes(data);
            assertArrayEquals(mac(jceProvider, key, data), mac(nativeProvider, key, data), "len=" + len);
        }
    }

    @Test
    public void testNativeMacLength() {
        NativeMacProvider nativeProvider = nativeProvider("HmacSHA256");
        if (nativeProvider == null) {
            System.out.println("SKIP: native mac unavailable");
            return;
        }
        assertEquals(32, nativeProvider.getMacLength());
        assertEquals(32, mac(nativeProvider, new byte[]{1}, new byte[]{2}).length);
    }

    /**
     * native provider 不支持的算法必须返回 null。
     */
    @Test
    public void testUnsupportedAlgorithmReturnsNull() {
        assertTrue(nativeProvider("HmacSHA512") == null, "HmacSHA512 不在 native 支持列表");
    }

    private static byte[] mac(MacProvider provider, byte[] key, byte[] data) {
        MacSession session = provider.newSession(key);
        session.update(data, 0, data.length);
        byte[] result = session.doFinal();
        assertNotNull(result);
        return result;
    }
}
