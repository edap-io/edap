package io.edap.crypto;

import io.edap.util.CryptUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CryptUtil} 迁移到 edap-crypto 的 {@code DigestProvider} 之后，
 * <b>摘要值必须与迁移前逐字节一致</b>。
 *
 * <p>这里的 golden 值是用<b>迁移前的原始实现</b>（每次 {@code MessageDigest.getInstance}）
 * 算出来的 —— 迁移只允许换实现，不允许换结果。</p>
 */
public class CryptUtilTest {

    @Test
    @DisplayName("md5(String) 与迁移前一致")
    public void testMd5String() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", CryptUtil.md5("hello"));
        assertEquals("2b039e086b679dcee0a645cea8c01a87", CryptUtil.md5("edap"));
    }

    @Test
    @DisplayName("md5(byte[]) 与迁移前一致")
    public void testMd5Bytes() {
        assertEquals("5d41402abc4b2a76b9719d911017c592",
                CryptUtil.md5("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("sha1(String) 与迁移前一致")
    public void testSha1String() {
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d",
                CryptUtil.hexStr(CryptUtil.sha1("hello")));
    }

    @Test
    @DisplayName("sha1 加盐与迁移前一致")
    public void testSha1Salted() {
        assertEquals("34cd57acc150672d09d0c5635c06d8c2f094a419",
                CryptUtil.hexStr(CryptUtil.sha1("hello".getBytes(StandardCharsets.UTF_8),
                        "salt".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("sha1 多轮迭代与迁移前一致（iterations=3）")
    public void testSha1Iterations() {
        assertEquals("b6141588c35044c77d506d3368ab741ef60ec67a",
                CryptUtil.hexStr(CryptUtil.sha1("hello".getBytes(StandardCharsets.UTF_8),
                        "salt".getBytes(StandardCharsets.UTF_8), 3)));
    }

    @Test
    @DisplayName("sha1 iterations=1 与加盐一次等价")
    public void testSha1IterationsOne() {
        assertArrayEquals(
                CryptUtil.sha1("hello".getBytes(StandardCharsets.UTF_8), "salt".getBytes(StandardCharsets.UTF_8)),
                CryptUtil.sha1("hello".getBytes(StandardCharsets.UTF_8), "salt".getBytes(StandardCharsets.UTF_8), 1));
    }

    @Test
    @DisplayName("sha256(String) 与迁移前一致")
    public void testSha256String() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                CryptUtil.sha256("hello"));
    }

    @Test
    @DisplayName("sha256(盐, 明文) 与迁移前一致")
    public void testSha256Salted() {
        assertEquals("965b73b43692abc0cbf8201cd29aa571e04d7606b9f6584c042277b22a19f9a1",
                CryptUtil.sha256("salt", "plain"));
    }

    @Test
    @DisplayName("sha256(byte[], offset, len) 只取窗口内的字节")
    public void testSha256Slice() {
        byte[] payload = "xxhelloxx".getBytes(StandardCharsets.UTF_8);
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                CryptUtil.hexStr(CryptUtil.sha256(payload, 2, 5)));
    }
}
