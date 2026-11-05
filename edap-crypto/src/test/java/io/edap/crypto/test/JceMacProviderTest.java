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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JCE MAC 实现 —— 必须与裸 {@link Mac} 逐字节一致。
 */
public class JceMacProviderTest {

    private static byte[] jce(String algorithm, byte[] key, byte[] data)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(key, algorithm));
        return mac.doFinal(data);
    }

    @Test
    @DisplayName("HmacSHA256 / HmacSHA1 与裸 Mac 一致")
    public void testMatchesRawMac() throws Exception {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes("UTF-8");
        byte[] data = "edap-crypto mac".getBytes("UTF-8");
        for (String algorithm : new String[]{"HmacSHA256", "HmacSHA1"}) {
            MacProvider provider = new JceMacProvider(algorithm);
            MacSession session = provider.newSession(key);
            session.update(data);
            assertArrayEquals(jce(algorithm, key, data), session.doFinal(), algorithm);
        }
    }

    @Test
    @DisplayName("分多次 update 等价于一次性 update")
    public void testChunkedUpdateEqualsOneShot() throws Exception {
        byte[] key = "k".getBytes("UTF-8");
        byte[] data = new byte[777];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        MacProvider provider = new JceMacProvider("HmacSHA256");

        MacSession oneShot = provider.newSession(key);
        oneShot.update(data);

        MacSession chunked = provider.newSession(key);
        int pos = 0;
        int[] sizes = {3, 11, 100, 663};
        for (int size : sizes) {
            chunked.update(data, pos, size);
            pos += size;
        }

        assertArrayEquals(oneShot.doFinal(), chunked.doFinal());
    }

    @Test
    @DisplayName("doFinal 之后 session 回到初始状态（key 保留、无数据）")
    public void testDoFinalKeepsKey() throws Exception {
        byte[] key = "0123456789abcdef".getBytes("UTF-8");
        byte[] data = "abc".getBytes("UTF-8");
        MacProvider provider = new JceMacProvider("HmacSHA256");
        MacSession session = provider.newSession(key);
        session.update(data);
        byte[] first = session.doFinal();
        assertArrayEquals(jce("HmacSHA256", key, data), first);

        // 回到初始状态（key 还在，但没数据了）⇒ 再算一次是「空数据」的 MAC
        assertArrayEquals(jce("HmacSHA256", key, new byte[0]), session.doFinal());
    }

    @Test
    @DisplayName("reset() 丢弃已喂的数据但保留 key")
    public void testResetKeepsKey() throws Exception {
        byte[] key = "0123456789abcdef".getBytes("UTF-8");
        byte[] data = "abc".getBytes("UTF-8");
        MacProvider provider = new JceMacProvider("HmacSHA256");
        MacSession session = provider.newSession(key);
        session.update("garbage".getBytes("UTF-8"));
        session.reset();
        session.update(data);
        assertArrayEquals(jce("HmacSHA256", key, data), session.doFinal());
    }

    @Test
    @DisplayName("换 key 后结果跟着变（init 必须重置状态）")
    public void testNewSessionWithDifferentKey() throws Exception {
        byte[] key1 = "0123456789abcdef".getBytes("UTF-8");
        byte[] key2 = "fedcba9876543210".getBytes("UTF-8");
        byte[] data = "abc".getBytes("UTF-8");
        MacProvider provider = new JceMacProvider("HmacSHA256");

        MacSession s1 = provider.newSession(key1);
        s1.update(data);
        byte[] r1 = s1.doFinal();

        MacSession s2 = provider.newSession(key2);
        s2.update(data);
        byte[] r2 = s2.doFinal();

        assertArrayEquals(jce("HmacSHA256", key1, data), r1);
        assertArrayEquals(jce("HmacSHA256", key2, data), r2);
        assertNotEquals(hex(r1), hex(r2));
    }

    @Test
    @DisplayName("getMacLength 与裸 Mac 一致")
    public void testMacLength() throws Exception {
        MacProvider provider = new JceMacProvider("HmacSHA256");
        assertEquals(Mac.getInstance("HmacSHA256").getMacLength(), provider.getMacLength());
        assertEquals(32, provider.getMacLength());
    }

    @Test
    @DisplayName("key 为 null / 空 ⇒ IllegalArgumentException")
    public void testInvalidKey() {
        MacProvider provider = new JceMacProvider("HmacSHA256");
        assertThrows(IllegalArgumentException.class, () -> provider.newSession(null));
        assertThrows(IllegalArgumentException.class, () -> provider.newSession(new byte[0]));
    }

    @Test
    @DisplayName("算法名非法 / 不支持 ⇒ IllegalArgumentException")
    public void testInvalidAlgorithm() {
        assertThrows(IllegalArgumentException.class, () -> new JceMacProvider(null));
        assertThrows(IllegalArgumentException.class, () -> new JceMacProvider(""));
        assertThrows(IllegalArgumentException.class, () -> new JceMacProvider("NoSuchMac"));
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
