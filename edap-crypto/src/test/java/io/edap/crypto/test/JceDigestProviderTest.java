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

import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.DigestSession;
import io.edap.crypto.digest.JceDigestProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JCE 摘要实现 —— 必须与裸 {@link MessageDigest} 逐字节一致。
 */
public class JceDigestProviderTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jce(String algorithm, byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance(algorithm).digest(data);
    }

    @Test
    @DisplayName("SHA-256 / SHA-1 / MD5 与裸 MessageDigest 一致")
    public void testMatchesRawMessageDigest() throws Exception {
        byte[] data = utf8("edap-crypto digest");
        for (String algorithm : new String[]{"SHA-256", "SHA-1", "MD5"}) {
            DigestProvider provider = new JceDigestProvider(algorithm);
            DigestSession session = provider.newSession();
            session.update(data);
            assertArrayEquals(jce(algorithm, data), session.digest(), algorithm);
        }
    }

    @Test
    @DisplayName("分多次 update 等价于一次性 update")
    public void testChunkedUpdateEqualsOneShot() {
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        DigestProvider provider = new JceDigestProvider("SHA-256");

        DigestSession oneShot = provider.newSession();
        oneShot.update(data);

        DigestSession chunked = provider.newSession();
        int pos = 0;
        int[] sizes = {1, 7, 100, 333, 559};
        for (int size : sizes) {
            chunked.update(data, pos, size);
            pos += size;
        }

        assertArrayEquals(oneShot.digest(), chunked.digest());
    }

    @Test
    @DisplayName("digest() 之后 session 回到初始状态（无数据）")
    public void testDigestResetsState() throws Exception {
        DigestProvider provider = new JceDigestProvider("SHA-256");
        DigestSession session = provider.newSession();
        session.update(utf8("abc"));
        byte[] first = session.digest();
        assertArrayEquals(jce("SHA-256", utf8("abc")), first);

        // 回到初始状态 ⇒ 不喂数据再算一次，得到的是「空输入」的摘要
        assertArrayEquals(jce("SHA-256", new byte[0]), session.digest());

        // 再喂一次数据，结果应与第一次相同（说明没有被污染）
        session.update(utf8("abc"));
        assertArrayEquals(first, session.digest());
    }

    @Test
    @DisplayName("reset() 丢弃已喂的数据")
    public void testResetDiscardsData() throws Exception {
        DigestProvider provider = new JceDigestProvider("SHA-256");
        DigestSession session = provider.newSession();
        session.update(utf8("garbage"));
        session.reset();
        session.update(utf8("abc"));

        assertArrayEquals(jce("SHA-256", utf8("abc")), session.digest());
    }

    @Test
    @DisplayName("两个会话互不干扰")
    public void testSessionsAreIndependent() throws Exception {
        DigestProvider provider = new JceDigestProvider("SHA-256");
        DigestSession s1 = provider.newSession();
        DigestSession s2 = provider.newSession();

        s1.update(utf8("abc"));
        s2.update(utf8("xyz"));

        assertArrayEquals(jce("SHA-256", utf8("abc")), s1.digest());
        assertArrayEquals(jce("SHA-256", utf8("xyz")), s2.digest());
    }

    @Test
    @DisplayName("getDigestLength 与 MessageDigest 一致")
    public void testDigestLength() throws Exception {
        DigestProvider provider = new JceDigestProvider("SHA-256");
        assertEquals(MessageDigest.getInstance("SHA-256").getDigestLength(), provider.getDigestLength());
        assertEquals(32, provider.getDigestLength());
    }

    @Test
    @DisplayName("算法名非法 / 不支持 ⇒ IllegalArgumentException")
    public void testInvalidAlgorithm() {
        assertThrows(IllegalArgumentException.class, () -> new JceDigestProvider(null));
        assertThrows(IllegalArgumentException.class, () -> new JceDigestProvider(""));
        assertThrows(IllegalArgumentException.class, () -> new JceDigestProvider("NO-SUCH-ALG"));
    }

    @Test
    @DisplayName("algorithm() 返回算法名")
    public void testAlgorithmName() {
        assertEquals("SHA-256", new JceDigestProvider("SHA-256").algorithm());
    }

    @Test
    @DisplayName("空输入的摘要与裸 MessageDigest 一致")
    public void testEmptyInput() throws Exception {
        DigestProvider provider = new JceDigestProvider("SHA-256");
        DigestSession session = provider.newSession();
        assertArrayEquals(jce("SHA-256", new byte[0]), session.digest());
        assertEquals(32, session.digest().length);
    }

    @Test
    @DisplayName("update(byte[]) 默认方法等价于 update(data, 0, len)")
    public void testUpdateDefaultOverload() {
        DigestProvider provider = new JceDigestProvider("SHA-256");
        DigestSession s1 = provider.newSession();
        s1.update(utf8("hello"));

        DigestSession s2 = provider.newSession();
        byte[] data = utf8("hello");
        s2.update(data, 0, data.length);

        assertArrayEquals(s1.digest(), s2.digest());
        assertFalse(Arrays.equals(s1.digest(), new byte[32]));
    }
}
