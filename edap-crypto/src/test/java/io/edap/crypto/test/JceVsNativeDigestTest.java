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
import io.edap.crypto.digest.NativeDigestProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * native 摘要 vs JCE 摘要 —— 逐字节一致。
 *
 * <p><b>edap-native 在 edap-crypto 是 test scope</b>，所以 native 不可用时
 * （edap-native 不在 classpath / 当前平台没有预编译产物）整类跳过，不能失败。</p>
 */
public class JceVsNativeDigestTest {

    /**
     * native provider；不可用时返回 {@code null}（测试据此跳过）。
     */
    private static NativeDigestProvider nativeProvider(String algorithm) {
        return NativeDigestProvider.tryCreate(algorithm);
    }

    @Test
    public void testSha256NativeEqualsJce() {
        NativeDigestProvider nativeProvider = nativeProvider("SHA-256");
        if (nativeProvider == null) {
            System.out.println("SKIP: native digest unavailable");
            return;
        }
        JceDigestProvider jceProvider = new JceDigestProvider("SHA-256");
        byte[] data = "hello edap crypto".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(digest(jceProvider, data, 0, data.length),
                digest(nativeProvider, data, 0, data.length));
    }

    @Test
    public void testSha1NativeEqualsJce() {
        NativeDigestProvider nativeProvider = nativeProvider("SHA-1");
        if (nativeProvider == null) {
            System.out.println("SKIP: native digest unavailable");
            return;
        }
        JceDigestProvider jceProvider = new JceDigestProvider("SHA-1");
        byte[] data = "hello edap crypto".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(digest(jceProvider, data, 0, data.length),
                digest(nativeProvider, data, 0, data.length));
    }

    @Test
    public void testMd5NativeEqualsJce() {
        NativeDigestProvider nativeProvider = nativeProvider("MD5");
        if (nativeProvider == null) {
            System.out.println("SKIP: native digest unavailable");
            return;
        }
        JceDigestProvider jceProvider = new JceDigestProvider("MD5");
        byte[] data = "hello edap crypto".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(digest(jceProvider, data, 0, data.length),
                digest(nativeProvider, data, 0, data.length));
    }

    /**
     * 分片喂入（多次 update）与一次性喂入必须同结果 —— native 侧是一次性 API，
     * 累加在 Java 侧做，这里验证累加逻辑没错。
     */
    @Test
    public void testChunkedUpdateEqualsOneShot() {
        NativeDigestProvider nativeProvider = nativeProvider("SHA-256");
        if (nativeProvider == null) {
            System.out.println("SKIP: native digest unavailable");
            return;
        }
        JceDigestProvider jceProvider = new JceDigestProvider("SHA-256");

        byte[] data = new byte[1000];
        new Random(42L).nextBytes(data);

        DigestSession nativeSession = nativeProvider.newSession();
        DigestSession jceSession = jceProvider.newSession();
        int chunk = 137;
        for (int off = 0; off < data.length; off += chunk) {
            int len = Math.min(chunk, data.length - off);
            nativeSession.update(data, off, len);
            jceSession.update(data, off, len);
        }
        assertArrayEquals(jceSession.digest(), nativeSession.digest());
    }

    /**
     * 随机长度对拍。
     */
    @Test
    public void testRandomPayloadsMatch() {
        NativeDigestProvider nativeProvider = nativeProvider("SHA-256");
        if (nativeProvider == null) {
            System.out.println("SKIP: native digest unavailable");
            return;
        }
        JceDigestProvider jceProvider = new JceDigestProvider("SHA-256");
        Random random = new Random(20231104L);
        int[] lengths = {0, 1, 55, 56, 63, 64, 65, 1024, 65536};
        for (int len : lengths) {
            byte[] data = new byte[len];
            random.nextBytes(data);
            assertArrayEquals(digest(jceProvider, data, 0, len),
                    digest(nativeProvider, data, 0, len), "len=" + len);
        }
    }

    /**
     * native provider 报告的摘要长度必须与实际输出长度一致。
     */
    @Test
    public void testNativeDigestLength() {
        NativeDigestProvider nativeProvider = nativeProvider("SHA-256");
        if (nativeProvider == null) {
            System.out.println("SKIP: native digest unavailable");
            return;
        }
        assertEquals(32, nativeProvider.getDigestLength());
        assertEquals(32, digest(nativeProvider, new byte[10], 0, 10).length);
    }

    /**
     * native provider 不支持的算法（JCE 名）必须返回 null，让调用方降级。
     */
    @Test
    public void testUnsupportedAlgorithmReturnsNull() {
        assertTrue(nativeProvider("SHA3-256") == null, "SHA3-256 不在 native 支持列表");
    }

    private static byte[] digest(DigestProvider provider, byte[] data, int offset, int len) {
        DigestSession session = provider.newSession();
        session.update(data, offset, len);
        byte[] result = session.digest();
        assertNotNull(result);
        return result;
    }
}
