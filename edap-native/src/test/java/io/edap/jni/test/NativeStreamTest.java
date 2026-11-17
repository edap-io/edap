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

package io.edap.jni.test;

import io.edap.jni.Native;
import io.edap.jni.crypto.NativeStream;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NativeStream} 正确性：与 JDK 的 {@link MessageDigest} / {@link javax.crypto.Mac}
 * 逐字节比对。
 *
 * <p><b>为什么单独测这一层</b>：edap-crypto 侧只保证「provider 的结果与 JCE 一致」，
 * 但流式入口的语义（分块喂入、空输入、finish 释放句柄）是 edap-crypto 依赖的<b>契约</b> ——
 * 契约破了，最先坏掉的是 edap-crypto 的会话语义（「digest 后回到初始状态」）。</p>
 *
 * <p>native 不可用时（不在支持的平台之一）整类跳过。</p>
 */
public class NativeStreamTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static boolean nativeReady() {
        Native.loadLibrary();
        return Native.ENABLE_NATIVE;
    }

    @Test
    public void testSha256MatchesJce() throws Exception {
        if (!nativeReady()) {
            return;
        }
        assertDigestMatches("SHA-256", NativeStream.ALG_SHA256, 32);
    }

    @Test
    public void testSha1MatchesJce() throws Exception {
        if (!nativeReady()) {
            return;
        }
        assertDigestMatches("SHA-1", NativeStream.ALG_SHA1, 20);
    }

    @Test
    public void testMd5MatchesJce() throws Exception {
        if (!nativeReady()) {
            return;
        }
        assertDigestMatches("MD5", NativeStream.ALG_MD5, 16);
    }

    /**
     * 分块喂入必须与一次性喂入同结果 —— 这正是「流式」的意义。
     */
    @Test
    public void testChunkedUpdateMatchesOneShot() throws Exception {
        if (!nativeReady()) {
            return;
        }
        byte[] data = new byte[1000];
        new Random(7L).nextBytes(data);

        long ctx = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, data, 0, 0);
        for (int off = 0; off < data.length; off += 137) {
            int len = Math.min(137, data.length - off);
            ctx = NativeStream.update(ctx, NativeStream.ALG_SHA256, null, 0, data, off, len);
        }
        byte[] chunked = NativeStream.finish(ctx);

        assertArrayEquals(jceDigest("SHA-256", data), chunked);
    }

    /**
     * 空输入 —— 与 JDK 的空摘要一致。
     */
    @Test
    public void testEmptyInput() throws Exception {
        if (!nativeReady()) {
            return;
        }
        long ctx = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, new byte[0], 0, 0);
        byte[] actual = NativeStream.finish(ctx);

        assertArrayEquals(jceDigest("SHA-256", new byte[0]), actual);
    }

    /**
     * HMAC-SHA256 / HMAC-SHA1 与 JCE 一致（含 key 长于 block size 的分支）。
     */
    @Test
    public void testHmacMatchesJce() throws Exception {
        if (!nativeReady()) {
            return;
        }
        assertMacMatches("HmacSHA256", NativeStream.ALG_HMAC_SHA256, KEY);
        assertMacMatches("HmacSHA1", NativeStream.ALG_HMAC_SHA1, KEY);
        assertMacMatches("HmacSHA256", NativeStream.ALG_HMAC_SHA256,
                "0123456789012345678901234567890123456789012345678901234567890123456789"
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * ★ {@code update} 兼做「建上下文」：句柄为 0 时第一次调用必须建出上下文。
     *
     * <p>这条守的是「把 init 并进 update」这个设计：JNI 往返在 Solaris/SPARC 上不便宜，
     * 分成两个入口 ⇒ 每次操作多一次 native 调用。</p>
     */
    @Test
    public void testUpdateCreatesContext() throws Exception {
        if (!nativeReady()) {
            return;
        }
        byte[] data = "hello edap crypto".getBytes(StandardCharsets.UTF_8);

        long ctx = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, data, 0, data.length);
        assertNotEquals(0, ctx, "第一次 update 必须建出上下文");

        // 句柄已经存在 ⇒ 后面的 update 原样返回同一个句柄
        long same = NativeStream.update(ctx, NativeStream.ALG_SHA256, null, 0, data, 0, data.length);
        assertEquals(ctx, same, "已存在的上下文必须原样返回");

        assertArrayEquals(jceDigest("SHA-256", concat(data, data)), NativeStream.finish(ctx));
    }

    /**
     * {@code finish} 会释放上下文 —— 之后<b>不能</b>再用这个句柄。
     *
     * <p>这里只验证「正常流程」：finish 之后再建一个新上下文，工作正常。
     * （重复 finish 是<b>调用方的错</b>（等价于 C 里的 double free），不在这里制造。）</p>
     */
    @Test
    public void testFinishThenReuse() throws Exception {
        if (!nativeReady()) {
            return;
        }
        byte[] data = "second round".getBytes(StandardCharsets.UTF_8);

        long first = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, data, 0, data.length);
        assertArrayEquals(jceDigest("SHA-256", data), NativeStream.finish(first));

        // finish 之后重新建 —— 新上下文工作正常
        long second = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, data, 0, data.length);
        assertArrayEquals(jceDigest("SHA-256", data), NativeStream.finish(second));
    }

    /**
     * {@code free} 放弃上下文 —— 调用本身不崩。
     */
    @Test
    public void testFreeReleasesContext() {
        if (!nativeReady()) {
            return;
        }
        long ctx = NativeStream.update(0, NativeStream.ALG_SHA256, null, 0, new byte[0], 0, 0);
        NativeStream.free(ctx);
        // 句柄已释放；这里只验证调用本身不崩（不重复使用句柄）
        assertTrue(true);
    }

    private static void assertDigestMatches(String jceAlgorithm, int alg, int digestLength) throws Exception {
        Random random = new Random(11L);
        int[] lengths = {0, 1, 55, 56, 63, 64, 65, 1024, 65536};
        for (int len : lengths) {
            byte[] data = new byte[len];
            random.nextBytes(data);

            long ctx = NativeStream.update(0, alg, null, 0, data, 0, len);
            byte[] actual = NativeStream.finish(ctx);

            assertEquals(digestLength, actual.length, "len=" + len);
            assertArrayEquals(jceDigest(jceAlgorithm, data), actual, "len=" + len);
        }
    }

    private static void assertMacMatches(String jceAlgorithm, int alg, byte[] key) throws Exception {
        byte[] data = "hello edap crypto".getBytes(StandardCharsets.UTF_8);

        long ctx = NativeStream.update(0, alg, key, key.length, data, 0, data.length);
        byte[] actual = NativeStream.finish(ctx);

        Mac mac = Mac.getInstance(jceAlgorithm);
        mac.init(new SecretKeySpec(key, jceAlgorithm));
        assertArrayEquals(mac.doFinal(data), actual);
    }

    private static byte[] jceDigest(String algorithm, byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        return md.digest(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
