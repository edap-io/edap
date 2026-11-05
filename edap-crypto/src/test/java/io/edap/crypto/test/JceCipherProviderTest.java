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

import io.edap.crypto.cipher.CipherProvider;
import io.edap.crypto.cipher.CipherSession;
import io.edap.crypto.cipher.JceCipherProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCE 对称加密实现 —— 往返、篡改检测、参数校验。
 */
public class JceCipherProviderTest {

    private static final byte[] KEY_256 = new byte[32];
    private static final byte[] KEY_128 = new byte[16];
    private static final byte[] IV_12 = new byte[12];

    static {
        for (int i = 0; i < KEY_256.length; i++) {
            KEY_256[i] = (byte) i;
        }
        for (int i = 0; i < KEY_128.length; i++) {
            KEY_128[i] = (byte) i;
        }
        for (int i = 0; i < IV_12.length; i++) {
            IV_12[i] = (byte) (0xA0 + i);
        }
    }

    @Test
    @DisplayName("AES-256-GCM 加解密往返")
    public void testAes256GcmRoundTrip() throws Exception {
        CipherProvider provider = new JceCipherProvider("AES/GCM/NoPadding", 256, 12, 128);
        byte[] plain = "edap-crypto cipher round trip".getBytes("UTF-8");

        CipherSession enc = provider.newSession(KEY_256, IV_12, Cipher.ENCRYPT_MODE);
        byte[] cipherText = enc.doFinal(plain);

        CipherSession dec = provider.newSession(KEY_256, IV_12, Cipher.DECRYPT_MODE);
        byte[] recovered = dec.doFinal(cipherText);

        assertArrayEquals(plain, recovered);
        // GCM 密文 = 明文 + 16 字节 tag
        assertEquals(plain.length + 16, cipherText.length);
    }

    @Test
    @DisplayName("AES-128-GCM 加解密往返")
    public void testAes128GcmRoundTrip() throws Exception {
        CipherProvider provider = new JceCipherProvider("AES/GCM/NoPadding", 128, 12, 128);
        byte[] plain = "aes-128".getBytes("UTF-8");

        byte[] cipherText = provider.newSession(KEY_128, IV_12, Cipher.ENCRYPT_MODE).doFinal(plain);
        byte[] recovered = provider.newSession(KEY_128, IV_12, Cipher.DECRYPT_MODE).doFinal(cipherText);

        assertArrayEquals(plain, recovered);
    }

    @Test
    @DisplayName("与裸 JCE Cipher 密文逐字节一致（同一 key/iv）")
    public void testMatchesRawJce() throws Exception {
        CipherProvider provider = new JceCipherProvider("AES/GCM/NoPadding", 256, 12, 128);
        byte[] plain = "compare me".getBytes("UTF-8");

        byte[] mine = provider.newSession(KEY_256, IV_12, Cipher.ENCRYPT_MODE).doFinal(plain);

        Cipher raw = Cipher.getInstance("AES/GCM/NoPadding");
        raw.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY_256, "AES"), new GCMParameterSpec(128, IV_12));
        byte[] theirs = raw.doFinal(plain);

        assertArrayEquals(theirs, mine);
    }

    @Test
    @DisplayName("密文被篡改 ⇒ AEADBadTagException 原样透出")
    public void testTamperedCipherTextThrows() throws Exception {
        CipherProvider provider = new JceCipherProvider("AES/GCM/NoPadding", 256, 12, 128);
        byte[] plain = "tamper me".getBytes("UTF-8");
        byte[] cipherText = provider.newSession(KEY_256, IV_12, Cipher.ENCRYPT_MODE).doFinal(plain);

        // 改一个字节（改 tag）
        cipherText[cipherText.length - 1] ^= 0x01;

        CipherSession dec = provider.newSession(KEY_256, IV_12, Cipher.DECRYPT_MODE);
        GeneralSecurityException e = assertThrows(GeneralSecurityException.class, () -> dec.doFinal(cipherText));
        assertTrue(e instanceof AEADBadTagException, "expected AEADBadTagException, got " + e.getClass());
    }

    @Test
    @DisplayName("明文被篡改 ⇒ AEADBadTagException")
    public void testTamperedPlainTextThrows() throws Exception {
        CipherProvider provider = new JceCipherProvider("AES/GCM/NoPadding", 256, 12, 128);
        byte[] plain = "tamper me too".getBytes("UTF-8");
        byte[] cipherText = provider.newSession(KEY_256, IV_12, Cipher.ENCRYPT_MODE).doFinal(plain);

        cipherText[0] ^= 0x01;

        CipherSession dec = provider.newSession(KEY_256, IV_12, Cipher.DECRYPT_MODE);
        assertThrows(AEADBadTagException.class, () -> dec.doFinal(cipherText));
    }

    @Test
    @DisplayName("key 长度不对 ⇒ InvalidKeyException")
    public void testWrongKeyLengthThrows() {
        CipherProvider provider = new JceCipherProvider("AES/GCM/NoPadding", 256, 12, 128);
        assertThrows(InvalidKeyException.class,
                () -> provider.newSession(KEY_128, IV_12, Cipher.ENCRYPT_MODE));
        assertThrows(InvalidKeyException.class,
                () -> provider.newSession(null, IV_12, Cipher.ENCRYPT_MODE));
    }

    @Test
    @DisplayName("IV 长度不对 ⇒ InvalidAlgorithmParameterException")
    public void testWrongIvLengthThrows() {
        CipherProvider provider = new JceCipherProvider("AES/GCM/NoPadding", 256, 12, 128);
        assertThrows(InvalidAlgorithmParameterException.class,
                () -> provider.newSession(KEY_256, new byte[16], Cipher.ENCRYPT_MODE));
        assertThrows(InvalidAlgorithmParameterException.class,
                () -> provider.newSession(KEY_256, null, Cipher.ENCRYPT_MODE));
    }

    @Test
    @DisplayName("forTransformation 推断 GCM 参数")
    public void testForTransformation() {
        JceCipherProvider provider = JceCipherProvider.forTransformation("AES/GCM/NoPadding");
        assertEquals("AES/GCM/NoPadding", provider.getTransformation());
        assertEquals(256, provider.getKeyLength());
        assertEquals(12, provider.getIvLength());
        assertEquals(128, provider.getTagLengthBits());
        assertTrue(provider.isAead());
    }

    @Test
    @DisplayName("非 AEAD transformation 不带 tag")
    public void testForTransformationNonAead() {
        JceCipherProvider provider = JceCipherProvider.forTransformation("AES/CBC/PKCS5Padding");
        assertEquals(16, provider.getIvLength());
        assertEquals(0, provider.getTagLengthBits());
        assertFalse(provider.isAead());
    }

    @Test
    @DisplayName("不支持的 transformation ⇒ IllegalArgumentException")
    public void testUnsupportedTransformation() {
        assertThrows(IllegalArgumentException.class, () -> JceCipherProvider.forTransformation("NoSuchCipher"));
        assertThrows(IllegalArgumentException.class, () -> new JceCipherProvider(null, 256, 12, 128));
        assertThrows(IllegalArgumentException.class, () -> new JceCipherProvider("", 256, 12, 128));
    }

    @Test
    @DisplayName("key 长度必须为 8 的倍数且大于 0")
    public void testInvalidKeyLengthInConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new JceCipherProvider("AES/GCM/NoPadding", 0, 12, 128));
        assertThrows(IllegalArgumentException.class, () -> new JceCipherProvider("AES/GCM/NoPadding", -128, 12, 128));
        assertThrows(IllegalArgumentException.class, () -> new JceCipherProvider("AES/GCM/NoPadding", 100, 12, 128));
    }

    @Test
    @DisplayName("doFinal(offset, len) 只处理指定区间")
    public void testDoFinalWithOffset() throws Exception {
        CipherProvider provider = new JceCipherProvider("AES/GCM/NoPadding", 256, 12, 128);
        byte[] buffer = "XXhello worldYY".getBytes("UTF-8");
        int offset = 2;
        int len = "hello world".length();

        byte[] cipherText = provider.newSession(KEY_256, IV_12, Cipher.ENCRYPT_MODE).doFinal(buffer, offset, len);

        CipherSession dec = provider.newSession(KEY_256, IV_12, Cipher.DECRYPT_MODE);
        assertArrayEquals("hello world".getBytes("UTF-8"), dec.doFinal(cipherText));
    }

    @Test
    @DisplayName("不同 IV ⇒ 不同密文")
    public void testDifferentIvDifferentCipherText() throws Exception {
        CipherProvider provider = new JceCipherProvider("AES/GCM/NoPadding", 256, 12, 128);
        byte[] plain = "same plain".getBytes("UTF-8");
        byte[] iv2 = new byte[12];
        System.arraycopy(IV_12, 0, iv2, 0, 12);
        iv2[0] = (byte) (IV_12[0] ^ 0xFF);

        byte[] c1 = provider.newSession(KEY_256, IV_12, Cipher.ENCRYPT_MODE).doFinal(plain);
        byte[] c2 = provider.newSession(KEY_256, iv2, Cipher.ENCRYPT_MODE).doFinal(plain);

        assertFalse(java.util.Arrays.equals(c1, c2));
    }
}
