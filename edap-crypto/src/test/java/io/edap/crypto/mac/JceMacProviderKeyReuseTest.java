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

package io.edap.crypto.mac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link JceMacProvider} —— 「同一把 key 不重复 init」这条性能不变量。
 *
 * <p><b>背景</b>：Solaris 上 {@code HmacSHA256} 落在 {@code SunPKCS11-Solaris}，
 * key 必须导进 token。实测（build-01，Oracle JDK 17.0.19，
 * {@code io.edap.crypto.benchmark.CryptoCostProbe}）：</p>
 *
 * <table border="1">
 *   <caption>同一台机器上的两种写法</caption>
 *   <tr><th>写法</th><th>ns/op</th></tr>
 *   <tr><td>{@code Mac.init(同一个 SecretKeySpec 对象)}</td><td>10 385</td></tr>
 *   <tr><td>{@code Mac.init(每次 new 一个 SecretKeySpec，字节相同)}</td><td><b>133 667</b></td></tr>
 * </table>
 *
 * <p>⇒ 早先的实现（每次操作 {@code new SecretKeySpec}）<b>每次操作都重新导一次 key</b>，
 * 一次 HMAC 的固定成本从 ~12μs 涨到 ~136μs —— 比一次 OpenSSL HMAC 还贵 40 倍。
 * 这条代价在开发机上完全看不出来（SunJCE 走软件路径，~40ns），
 * <b>同一个缺陷在两台机器上的时间差是 3000 倍</b>
 * ⇒ 时间断言要么在开发机上永远绿、要么在 Solaris 上永远 flaky。
 * 能跨平台守住它的只有「init 被调了几次 / key 对象造了几个」这个<b>结构性事实</b>。</p>
 *
 * <p><b>为什么计数器在 provider 里面</b>：早先的写法是往 JCE provider 列表头部插一个
 * 替身 provider，靠 JCE 选中它来数 {@code init}。那个办法<b>在 Solaris 上不成立</b>
 * （JCE 没选中替身 ⇒ 计数恒为 0）⇒ 改成在 provider 内部、<b>init 的调用现场</b>直接计数：
 * 平台无关，且计数点就是被守的那一行。</p>
 */
public class JceMacProviderKeyReuseTest {

    private static final String ALGORITHM = "HmacSHA256";

    private static final byte[] KEY_A = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] KEY_B = "fedcba9876543210fedcba9876543210"
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] DATA = "edap-crypto mac".getBytes(StandardCharsets.UTF_8);

    /**
     * ★ 核心不变量：<b>同一把 key 连续多次操作 ⇒ 只 init 一次、只造一个 key 对象。</b>
     *
     * <p>这是 Solaris 上省掉 ~130μs/操作的那一刀。</p>
     */
    @Test
    @DisplayName("同一把 key 连续 5 次操作 ⇒ 只 init 一次")
    public void testSameKeyDoesNotReInit() throws Exception {
        byte[] expected = expected(DATA, KEY_A);

        JceMacProvider provider = new JceMacProvider(ALGORITHM);

        for (int i = 0; i < 5; i++) {
            MacSession session = provider.newSession(KEY_A);
            session.update(DATA);
            assertArrayEquals(expected, session.doFinal(), "第 " + i + " 次结果必须一致");
        }

        assertEquals(1, provider.initCount(),
                "同一把 key 不该重复 init（Solaris 上每次 init ≈ 130μs 的 token 导入）");
        assertEquals(1, provider.keySpecCount(),
                "同一把 key 不该每次都 new 一个 SecretKeySpec");
    }

    /**
     * 换 key ⇒ 必须重新 init（否则会算出上一个 key 的 MAC）。
     */
    @Test
    @DisplayName("换 key ⇒ 会重新 init，且结果跟着变")
    public void testDifferentKeyReInits() throws Exception {
        byte[] expectedA = expected(DATA, KEY_A);
        byte[] expectedB = expected(DATA, KEY_B);

        JceMacProvider provider = new JceMacProvider(ALGORITHM);

        byte[] r1 = sign(provider, KEY_A, DATA);
        byte[] r2 = sign(provider, KEY_B, DATA);

        assertEquals(2, provider.initCount(), "换 key 必须重新 init");
        assertEquals(2, provider.keySpecCount(), "换 key 必须造新的 key 对象");
        assertArrayEquals(expectedA, r1);
        assertArrayEquals(expectedB, r2);
        assertNotEquals(hex(r1), hex(r2));
    }

    /**
     * A → B → A：每次换 key 都要 init 一次（不能靠「记住第一个 key」蒙对）。
     */
    @Test
    @DisplayName("A→B→A 来回切 ⇒ 每次切换都重新 init")
    public void testKeySwitchBackReInits() throws Exception {
        byte[] expectedA = expected(DATA, KEY_A);

        JceMacProvider provider = new JceMacProvider(ALGORITHM);

        sign(provider, KEY_A, DATA);
        sign(provider, KEY_B, DATA);
        byte[] r3 = sign(provider, KEY_A, DATA);

        assertEquals(3, provider.initCount(), "每次换 key 都要重新 init");
        assertArrayEquals(expectedA, r3, "切回 A 必须还是 A 的 MAC");
    }

    /**
     * 不换 key 时连续 doFinal：第一次算的是有数据的 MAC，第二次是空数据的 MAC
     * —— 与 JCE {@code Mac.doFinal()} 的语义一致（见 {@code MacSession#doFinal()} 契约）。
     *
     * <p>这条守的是「跳过 init 之后，实例的状态仍然是对的」。</p>
     */
    @Test
    @DisplayName("同一 session 连续 doFinal ⇒ 第二个是空数据的 MAC")
    public void testDoFinalTwiceOnSameSession() throws Exception {
        byte[] expectedData = expected(DATA, KEY_A);
        byte[] expectedEmpty = expected(new byte[0], KEY_A);

        JceMacProvider provider = new JceMacProvider(ALGORITHM);
        MacSession session = provider.newSession(KEY_A);
        session.update(DATA);
        assertArrayEquals(expectedData, session.doFinal());
        assertArrayEquals(expectedEmpty, session.doFinal(),
                "doFinal 之后 session 回到初始状态（key 保留、无数据）");
        assertEquals(1, provider.initCount(), "两次 doFinal 不该重新 init");
    }

    /**
     * 同一把 key 的两个 session 交替使用 ⇒ 结果互不干扰。
     */
    @Test
    @DisplayName("同一 key 的两个 session 交替用 ⇒ 结果一致")
    public void testTwoSessionsSameKey() throws Exception {
        byte[] expected = expected(DATA, KEY_A);

        JceMacProvider provider = new JceMacProvider(ALGORITHM);
        MacSession s1 = provider.newSession(KEY_A);
        MacSession s2 = provider.newSession(KEY_A);

        s1.update(DATA);
        s2.update(DATA);
        assertArrayEquals(expected, s1.doFinal());
        assertArrayEquals(expected, s2.doFinal());
    }

    private static byte[] sign(MacProvider provider, byte[] key, byte[] data) {
        MacSession session = provider.newSession(key);
        session.update(data);
        return session.doFinal();
    }

    /**
     * 裸 JCE 的结果（用来做参照）。
     */
    private static byte[] expected(byte[] data, byte[] key) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(key, ALGORITHM));
        return mac.doFinal(data);
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
