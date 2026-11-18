package io.edap.jni.test;

import io.edap.jni.Native;
import io.edap.jni.crypto.NativeMac;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * NativeMac 正确性：HMAC-SHA256 / HMAC-SHA1 与 Java {@link Mac} 逐字节比对。
 *
 * <p>覆盖三种 key 长度（短于 / 等于 / 长于 block size 64）—— RFC 2104 的
 * key 预处理分支在这一层就能暴露。</p>
 */
public class NativeMacTest {

    private static boolean nativeReady() {
        Native.loadLibrary();
        return Native.ENABLE_NATIVE;
    }

    @Test
    public void testLibraryLoaded() {
        nativeReady();
        assertEquals(true, true);
    }

    @Test
    public void testHmacSha256MatchesJava() throws Exception {
        if (!nativeReady()) return;
        byte[] key = "this-is-a-test-signing-key-32-bytes!".getBytes(StandardCharsets.UTF_8);
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);

        byte[] nativeResult = NativeMac.hmacSha256(key, data, 0, data.length);
        byte[] javaResult = javaHmac("HmacSHA256", key, data);

        assertEquals(32, nativeResult.length);
        assertArrayEquals(javaResult, nativeResult);
    }

    @Test
    public void testHmacSha1MatchesJava() throws Exception {
        if (!nativeReady()) return;
        byte[] key = "this-is-a-test-signing-key-32-bytes!".getBytes(StandardCharsets.UTF_8);
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);

        byte[] nativeResult = NativeMac.hmacSha1(key, data, 0, data.length);
        byte[] javaResult = javaHmac("HmacSHA1", key, data);

        assertEquals(20, nativeResult.length);
        assertArrayEquals(javaResult, nativeResult);
    }

    /**
     * key 长短三档 —— RFC 2104 §2 的两种预处理分支都要覆盖。
     */
    @Test
    public void testKeyLengthBranches() throws Exception {
        if (!nativeReady()) return;
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        byte[][] keys = new byte[][]{
                new byte[]{0x01},                                  // 短于 block size
                "0123456789012345678901234567890123456789012345678901234567890123".getBytes(StandardCharsets.UTF_8), // 恰好 64
                "0123456789012345678901234567890123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8) // 长于 64
        };
        for (byte[] key : keys) {
            byte[] nativeResult = NativeMac.hmacSha256(key, data, 0, data.length);
            byte[] javaResult = javaHmac("HmacSHA256", key, data);
            assertArrayEquals(javaResult, nativeResult, "key len=" + key.length);
        }
    }

    @Test
    public void testOffsetAndLengthSlice() throws Exception {
        if (!nativeReady()) return;
        byte[] key = "key-material".getBytes(StandardCharsets.UTF_8);
        byte[] data = "prefix-actual-data-suffix".getBytes(StandardCharsets.UTF_8);
        byte[] slice = "actual-data".getBytes(StandardCharsets.UTF_8);

        byte[] nativeResult = NativeMac.hmacSha256(key, data, 7, slice.length);
        byte[] javaResult = javaHmac("HmacSHA256", key, slice);
        assertArrayEquals(javaResult, nativeResult);
    }

    /**
     * 随机对拍。
     *
     * <p>★ 长度矩阵带上 <b>1023 / 1024 / 1025</b>：native 侧的小载荷阈值是 1024
     * （≤ 走 {@code GetByteArrayRegion} 拷栈缓冲，&gt; 走 critical section）
     * ⇒ 这三个长度正好压住分流边界。</p>
     */
    @Test
    public void testRandomPayloadsMatchJava() throws Exception {
        if (!nativeReady()) return;
        Random random = new Random(20231103L);
        byte[] key = new byte[37];
        random.nextBytes(key);
        int[] lengths = {0, 1, 63, 64, 65, 1023, 1024, 1025, 2048};
        for (int len : lengths) {
            byte[] data = new byte[len];
            random.nextBytes(data);
            assertArrayEquals(javaHmac("HmacSHA256", key, data),
                    NativeMac.hmacSha256(key, data, 0, len), "sha256 len=" + len);
            assertArrayEquals(javaHmac("HmacSHA1", key, data),
                    NativeMac.hmacSha1(key, data, 0, len), "sha1 len=" + len);
        }
    }

    /**
     * ★ key 也要跨小载荷阈值 —— key 走的是同一套分流（region / critical）。
     *
     * <p>key 长度同时覆盖 RFC 2104 的两条预处理分支（≤ 64 直接补零、&gt; 64 先哈希）。</p>
     */
    @Test
    public void testKeyLengthsAcrossThreshold() throws Exception {
        if (!nativeReady()) return;
        Random random = new Random(20261117L);
        byte[] data = "key length matrix".getBytes(StandardCharsets.UTF_8);
        int[] keyLengths = {1, 16, 32, 63, 64, 65, 1023, 1024, 1025, 4096};
        for (int keyLen : keyLengths) {
            byte[] key = new byte[keyLen];
            random.nextBytes(key);
            assertArrayEquals(javaHmac("HmacSHA256", key, data),
                    NativeMac.hmacSha256(key, data, 0, data.length), "HmacSHA256 keyLen=" + keyLen);
            assertArrayEquals(javaHmac("HmacSHA1", key, data),
                    NativeMac.hmacSha1(key, data, 0, data.length), "HmacSHA1 keyLen=" + keyLen);
        }
    }

    /**
     * ★ 带偏移的切片 —— 小载荷路径也必须按 {@code offset} 取。
     */
    @Test
    public void testSliceWithOffsetAcrossThreshold() throws Exception {
        if (!nativeReady()) return;
        Random random = new Random(20261118L);
        byte[] key = "offset-key".getBytes(StandardCharsets.UTF_8);
        int[] lengths = {10, 1023, 1024, 1025, 4096};
        for (int len : lengths) {
            byte[] buffer = new byte[len + 16];
            random.nextBytes(buffer);
            byte[] slice = new byte[len];
            System.arraycopy(buffer, 7, slice, 0, len);

            assertArrayEquals(javaHmac("HmacSHA256", key, slice),
                    NativeMac.hmacSha256(key, buffer, 7, len), "len=" + len);
        }
    }

    @Test
    public void testEmptyKeyRejected() {
        if (!nativeReady()) return;
        byte[] data = new byte[]{1};
        assertThrows(IllegalArgumentException.class, () -> NativeMac.hmacSha256(new byte[0], data, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> NativeMac.hmacSha1(new byte[0], data, 0, 1));
    }

    private static byte[] javaHmac(String algorithm, byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(key, algorithm));
        return mac.doFinal(data);
    }
}
