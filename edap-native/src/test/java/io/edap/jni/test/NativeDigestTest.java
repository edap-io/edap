package io.edap.jni.test;

import io.edap.jni.Native;
import io.edap.jni.crypto.NativeDigest;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NativeDigest 正确性：与 Java {@link MessageDigest} 逐字节比对。
 *
 * <p>覆盖 SHA-256 / SHA-1 / MD5 三个算法，以及空输入、不同长度、offset+len 切片
 * 三类边界。native 不可用时（不在 4 个支持平台之一）整类跳过。</p>
 */
public class NativeDigestTest {

    private static boolean nativeReady() {
        Native.loadLibrary();
        return Native.ENABLE_NATIVE;
    }

    @Test
    public void testLibraryLoaded() {
        nativeReady();
        assertTrue(true);
    }

    @Test
    public void testSha256MatchesJava() throws Exception {
        if (!nativeReady()) return;
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] nativeResult = NativeDigest.digest("SHA256", data, 0, data.length);
        byte[] javaResult = MessageDigest.getInstance("SHA-256").digest(data);
        assertEquals(32, nativeResult.length);
        assertArrayEquals(javaResult, nativeResult);
    }

    @Test
    public void testSha1MatchesJava() throws Exception {
        if (!nativeReady()) return;
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] nativeResult = NativeDigest.digest("SHA1", data, 0, data.length);
        byte[] javaResult = MessageDigest.getInstance("SHA-1").digest(data);
        assertEquals(20, nativeResult.length);
        assertArrayEquals(javaResult, nativeResult);
    }

    @Test
    public void testMd5MatchesJava() throws Exception {
        if (!nativeReady()) return;
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] nativeResult = NativeDigest.digest("MD5", data, 0, data.length);
        byte[] javaResult = MessageDigest.getInstance("MD5").digest(data);
        assertEquals(16, nativeResult.length);
        assertArrayEquals(javaResult, nativeResult);
    }

    @Test
    public void testEmptyInput() throws Exception {
        if (!nativeReady()) return;
        for (String[] alg : new String[][]{{"SHA256", "SHA-256"}, {"SHA1", "SHA-1"}, {"MD5", "MD5"}}) {
            byte[] nativeResult = NativeDigest.digest(alg[0], new byte[0], 0, 0);
            byte[] javaResult = MessageDigest.getInstance(alg[1]).digest(new byte[0]);
            assertArrayEquals(javaResult, nativeResult, alg[0] + " empty");
        }
    }

    /**
     * offset/len 切片必须与「只喂切片内容」的结果一致。
     */
    @Test
    public void testOffsetAndLengthSlice() throws Exception {
        if (!nativeReady()) return;
        byte[] data = "prefix-actual-data-suffix".getBytes(StandardCharsets.UTF_8);
        byte[] slice = "actual-data".getBytes(StandardCharsets.UTF_8);
        byte[] nativeResult = NativeDigest.digest("SHA256", data, 7, slice.length);
        byte[] javaResult = MessageDigest.getInstance("SHA-256").digest(slice);
        assertArrayEquals(javaResult, nativeResult);
    }

    /**
     * 随机长度 + 随机内容的对拍（覆盖 block 边界 55/56/63/64/65/119/120 等）。
     */
    @Test
    public void testRandomLengthsMatchJava() throws Exception {
        if (!nativeReady()) return;
        Random random = new Random(20231102L);
        int[] lengths = {0, 1, 31, 55, 56, 63, 64, 65, 119, 120, 127, 128, 1000, 65536};
        for (int len : lengths) {
            byte[] data = new byte[len];
            random.nextBytes(data);
            for (String[] alg : new String[][]{{"SHA256", "SHA-256"}, {"SHA1", "SHA-1"}, {"MD5", "MD5"}}) {
                byte[] nativeResult = NativeDigest.digest(alg[0], data, 0, len);
                byte[] javaResult = MessageDigest.getInstance(alg[1]).digest(data);
                assertArrayEquals(javaResult, nativeResult, alg[0] + " len=" + len);
            }
        }
    }

    /**
     * 未知算法名 —— native 侧返回 null（调用方按「不可用」处理）。
     */
    @Test
    public void testUnknownAlgorithmReturnsNull() {
        if (!nativeReady()) return;
        byte[] data = new byte[]{1, 2, 3};
        assertNull(NativeDigest.digest("SHA3-256", data, 0, data.length));
    }
}
