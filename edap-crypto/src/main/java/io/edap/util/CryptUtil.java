/*
 * Copyright 2020 The edap Project
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

package io.edap.util;

import io.edap.crypto.CryptoRegistry;
import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.DigestSession;
import io.edap.crypto.internal.CryptoDiagnostics;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 加解密相关的工具类
 *
 * <p><b>实现走 edap-crypto 的 {@link DigestProvider} 抽象</b>：原来每次调用都
 * {@code MessageDigest.getInstance(...)}（provider 查找 + spi 创建，1~3μs），
 * 现在由 provider 内部按线程复用实例。native（OpenSSL JNI）还是 JCE 由
 * {@code edap.crypto.digest.*} 开关决定。</p>
 *
 * <p><b>行为保持不变</b>：摘要值、异常类型、字符集语义都与迁移前一致 ——
 * 特别是 {@link #md5(String)} 仍用<b>平台默认字符集</b>（历史行为，
 * 改动会让已生成的 md5 标识全部变化）。</p>
 *
 * <p><b>★ 兜底到裸 JCE</b>：edap-crypto 现在位于<b>日志 bootstrap 路径</b>上
 * （{@code LoggerManager.init()} → SPI provider → appender 初始化 →
 * {@code EncoderGenerator} → {@code CryptUtil.md5}）。它一旦抛异常，
 * 会连带打死整个日志系统（SPI provider 实例化失败 ⇒ 全应用退化成 NopLogger）。
 * 所以这里捕获 RuntimeException 后退回<b>裸 JCE</b>
 * （{@code MessageDigest.getInstance}，完全绕开 edap-crypto），
 * 并把问题通过 {@link CryptoDiagnostics} 打到 stderr ——
 * 结果逐字节相同，不掩盖错误。</p>
 */
public class CryptUtil {

    public final static byte[] HEX_DIGITS = {
            '0' , '1' , '2' , '3' , '4' , '5' ,
            '6' , '7' , '8' , '9' , 'a' , 'b' ,
            'c' , 'd' , 'e' , 'f' , 'g' , 'h' ,
            'i' , 'j' , 'k' , 'l' , 'm' , 'n' ,
            'o' , 'p' , 'q' , 'r' , 's' , 't' ,
            'u' , 'v' , 'w' , 'x' , 'y' , 'z'
    };

    private CryptUtil() {}

    public static String md5(String plain) {
        // 保持历史的平台默认字符集行为（plain.getBytes()）—— 改用 UTF-8 会让
        // 非 ASCII 输入的 md5 值变化，进而影响已生成的 handler 名 / 转换器 key。
        return md5(plain.getBytes());
    }

    public static String md5(byte[] srcData) {
        byte[] secretBytes = digest("MD5", srcData, null, 1);
        String md5code = new BigInteger(1, secretBytes).toString(16);
        for (int i = 0; i < 32 - md5code.length(); i++) {
            md5code = "0" + md5code;
        }
        return md5code;
    }

    public static byte[] sha1(String input) {
        return sha1(input.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha1(byte[] input) {
        return digest("SHA-1", input, null, 1);
    }

    public static byte[] sha1(byte[] input, byte[] salt) {
        return digest("SHA-1", input, salt, 1);
    }

    public static byte[] sha1(byte[] input, byte[] salt, int iterations) {
        return digest("SHA-1", input, salt, iterations);
    }

    /**
     * 摘要 —— 支持「加盐 + 多轮迭代」。
     *
     * <p>语义与迁移前一致：{@code HASH(salt || input)}，然后把结果再哈希
     * {@code iterations - 1} 轮。</p>
     */
    private static byte[] digest(String algorithm, byte[] input, byte[] salt, int iterations) {
        return digest(algorithm, input, 0, input.length, salt, iterations);
    }

    /**
     * 摘要（指定区间）—— 支持「加盐 + 多轮迭代」。
     */
    private static byte[] digest(String algorithm, byte[] input, int offset, int len,
                                 byte[] salt, int iterations) {
        try {
            DigestProvider provider = CryptoRegistry.digest(algorithm);
            // session 与线程绑定（独占一个 JCE MessageDigest / native 累加器）
            DigestSession session = provider.newSession();
            if (salt != null) {
                session.update(salt);
            }
            session.update(input, offset, len);
            // digest() 之后 session 回到初始状态 ⇒ 迭代时直接接着喂上一轮结果
            byte[] result = session.digest();

            for (int i = 1; i < iterations; i++) {
                session.update(result);
                result = session.digest();
            }
            return result;
        } catch (RuntimeException e) {
            return fallbackDigest(algorithm, input, offset, len, salt, iterations, e);
        }
    }

    /**
     * 裸 JCE 兜底 —— 完全绕开 edap-crypto。
     *
     * <p>只有在 crypto 层抛异常时才会走到这里；正常路径永远不经过它，
     * 所以摘要值与迁移前逐字节相同。</p>
     */
    private static byte[] fallbackDigest(String algorithm, byte[] input, int offset, int len,
                                         byte[] salt, int iterations, RuntimeException cause) {
        CryptoDiagnostics.warn("crypto provider failed for " + algorithm + " (" + cause
                + "), falling back to plain JCE");
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            if (salt != null) {
                md.update(salt);
            }
            md.update(input, offset, len);
            byte[] result = md.digest();
            for (int i = 1; i < iterations; i++) {
                md.update(result);
                result = md.digest();
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            // 兜底也失败（算法根本不存在）⇒ 抛原始异常，保持既有语义
            throw cause;
        }
    }

    public static String sha256(String plain) {
        try {
            return hexStr(digest("SHA-256", plain.getBytes(StandardCharsets.UTF_8), null, 1));
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return "";
        }
    }

    public static byte[] sha256(byte[] plain, int offset, int len) {
        return digest("SHA-256", plain, offset, len, null, 1);
    }

    public static String sha256(String salt, String plain) {
        try {
            return hexStr(digest("SHA-256", (salt + plain).getBytes(StandardCharsets.UTF_8), null, 1));
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return "";
        }
    }

    public static String hexStr(byte[] b) {
        return hexStr(b, 0, b.length);
    }

    public static String hexStr(byte[] b, int pos, int len) {
        byte[] data = new byte[len << 1];
        int end = pos + len;
        int index = 0;
        for (int i = pos; i < end; i++) {
            int v = b[i] & 0xff;
            data[index++] = HEX_DIGITS[v >>> 4];
            data[index++] = HEX_DIGITS[v & 0x0f];
        }
        return new String(data);
    }

}
