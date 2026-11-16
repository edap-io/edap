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

package io.edap.crypto.cipher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link JceCipherProvider} —— 「同一把 key 复用同一个 {@code SecretKeySpec} 对象」这条性能不变量。
 *
 * <p><b>背景</b>：Solaris 上 {@code HmacSHA256} 落在 {@code SunPKCS11-Solaris}，
 * key 必须导进 token；实测 <b>JDK 的 token 导入缓存按 key <i>对象身份</i>走、跟字节无关</b>
 * （init 同一个 key 对象 10.4μs vs 每次 new 一个字节相同的对象 133.7μs，<b>差 12.9 倍</b>）。
 * 早先的实现每次操作都 {@code new SecretKeySpec(...)}
 * ⇒ 每次 {@code Cipher.init} 都要把 key 重新导进 token。
 * 这条代价在开发机上完全看不出来（SunJCE 走软件路径），
 * 所以只能靠「造了几个 key 对象」这个结构性事实来守。</p>
 *
 * <p><b>为什么计数的是「key 对象」而不是「init 次数」</b>：
 * cipher 的 {@code init} 带 IV，而 <b>AEAD 每条消息必须换 nonce</b> ⇒ init 躲不掉、
 * 次数必然是 N（那是语义要求，不是缺陷）。
 * 能守的只有「同一把 key ⇒ 传给 init 的是<b>同一个</b> key 对象」。</p>
 */
public class JceCipherProviderKeyReuseTest {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final byte[] KEY_A = new byte[32];

    private static final byte[] KEY_B = new byte[32];

    private static final byte[] PLAIN = "edap-crypto cipher".getBytes(StandardCharsets.UTF_8);

    static {
        for (int i = 0; i < KEY_A.length; i++) {
            KEY_A[i] = (byte) i;
            KEY_B[i] = (byte) (0xFF - i);
        }
    }

    /**
     * ★ 核心不变量：<b>同一把 key 连续多次操作 ⇒ 只造一个 key 对象。</b>
     *
     * <p>每次用不同的 IV —— GCM 的 nonce 必须每条消息都不同，
     * 复用 nonce 是调用方的错，而且 SunJCE 会直接拒绝（同实例同 IV 第二次加密抛
     * {@code Cannot reuse iv for GCM encryption}）。</p>
     */
    @Test
    @DisplayName("同一把 key 连续 3 次操作 ⇒ 只造 1 个 key 对象")
    public void testSameKeyReusesKeyObject() throws Exception {
        JceCipherProvider provider = new JceCipherProvider(TRANSFORMATION, 256, 12, 128);

        for (int i = 0; i < 3; i++) {
            byte[] cipherText = provider
                    .newSession(KEY_A, iv(i), Cipher.ENCRYPT_MODE)
                    .doFinal(PLAIN);
            assertEquals(PLAIN.length + 16, cipherText.length, "GCM 密文 = 明文 + 16 字节 tag");
        }

        assertEquals(1, provider.keySpecCount(),
                "同一把 key 不该每次都 new 一个 SecretKeySpec"
                        + "（Solaris 上每次 init ≈ 130μs 的 token 导入）");
        assertEquals(3, provider.initCount(), "init 次数 = 操作次数（IV 每条都换，init 躲不掉）");
    }

    /**
     * 换 key ⇒ 必须换 key 对象（否则会算出上一个 key 的密文）。
     */
    @Test
    @DisplayName("换 key ⇒ 造新的 key 对象")
    public void testDifferentKeyUsesDifferentKeyObject() throws Exception {
        JceCipherProvider provider = new JceCipherProvider(TRANSFORMATION, 256, 12, 128);

        provider.newSession(KEY_A, iv(0), Cipher.ENCRYPT_MODE).doFinal(PLAIN);
        provider.newSession(KEY_B, iv(1), Cipher.ENCRYPT_MODE).doFinal(PLAIN);

        assertEquals(2, provider.keySpecCount(), "换 key 必须换 key 对象");
    }

    /**
     * ★ 密文必须与裸 JCE 逐字节一致 —— 复用 key 对象不能改变任何输出。
     */
    @Test
    @DisplayName("复用 key 对象后密文仍与裸 JCE 逐字节一致")
    public void testCipherTextMatchesRawJce() throws Exception {
        byte[] expected = expected(PLAIN, KEY_A, iv(0));

        JceCipherProvider provider = new JceCipherProvider(TRANSFORMATION, 256, 12, 128);

        byte[] mine = provider.newSession(KEY_A, iv(0), Cipher.ENCRYPT_MODE).doFinal(PLAIN);

        assertArrayEquals(expected, mine);
    }

    /**
     * ★ 换回旧 key 后，密文必须跟着换（不能靠「记住第一个 key 对象」蒙对）。
     */
    @Test
    @DisplayName("A→B→A 来回切 ⇒ 每次切换都用对应的 key")
    public void testKeySwitchBackAndForth() throws Exception {
        // 参考值必须用「与被测调用完全相同的 IV」—— GCM 的密文随 IV 变
        byte[] expectedA = expected(PLAIN, KEY_A, iv(0));
        byte[] expectedB = expected(PLAIN, KEY_B, iv(1));
        byte[] expectedA2 = expected(PLAIN, KEY_A, iv(2));

        JceCipherProvider provider = new JceCipherProvider(TRANSFORMATION, 256, 12, 128);

        provider.newSession(KEY_A, iv(0), Cipher.ENCRYPT_MODE).doFinal(PLAIN);
        provider.newSession(KEY_B, iv(1), Cipher.ENCRYPT_MODE).doFinal(PLAIN);
        byte[] r3 = provider.newSession(KEY_A, iv(2), Cipher.ENCRYPT_MODE).doFinal(PLAIN);

        assertArrayEquals(expectedA2, r3, "切回 A 必须还是 A 的密文");
        assertNotEquals(hex(expectedA), hex(expectedB));
        assertEquals(3, provider.keySpecCount(), "A→B→A 造了 3 个 key 对象（B 换掉了 A 的）");
    }

    /**
     * 同一把 key 的两个 session 交替使用 ⇒ 结果互不干扰。
     *
     * <p>两个 session 用<b>不同的 IV</b>（GCM 的密文随 IV 变，IV 相同则密文本来就该不同），
     * 各自与「同 IV 的裸 JCE 参照值」比对 ⇒ 验的是「session 之间不串味」，
     * 而不是「两个 session 密文相同」。</p>
     */
    @Test
    @DisplayName("同一 key 的两个 session 交替用 ⇒ 各自结果都对")
    public void testTwoSessionsSameKey() throws Exception {
        byte[] expected1 = expected(PLAIN, KEY_A, iv(0));
        byte[] expected2 = expected(PLAIN, KEY_A, iv(1));

        JceCipherProvider provider = new JceCipherProvider(TRANSFORMATION, 256, 12, 128);
        CipherSession s1 = provider.newSession(KEY_A, iv(0), Cipher.ENCRYPT_MODE);
        CipherSession s2 = provider.newSession(KEY_A, iv(1), Cipher.ENCRYPT_MODE);

        assertArrayEquals(expected1, s1.doFinal(PLAIN));
        assertArrayEquals(expected2, s2.doFinal(PLAIN));
    }

    /**
     * 每次不同的 IV —— GCM 的 nonce 必须每条消息都不同。
     */
    private static byte[] iv(int index) {
        byte[] iv = new byte[12];
        iv[0] = (byte) index;
        iv[1] = (byte) (index >> 8);
        return iv;
    }

    /**
     * 裸 JCE 的结果（用来做参照）。
     */
    private static byte[] expected(byte[] plain, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return cipher.doFinal(plain);
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
