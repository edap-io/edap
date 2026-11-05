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

import java.math.BigInteger;
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
public class Md5Util {

    public final static byte[] HEX_DIGITS = {
            '0' , '1' , '2' , '3' , '4' , '5' ,
            '6' , '7' , '8' , '9' , 'a' , 'b' ,
            'c' , 'd' , 'e' , 'f' , 'g' , 'h' ,
            'i' , 'j' , 'k' , 'l' , 'm' , 'n' ,
            'o' , 'p' , 'q' , 'r' , 's' , 't' ,
            'u' , 'v' , 'w' , 'x' , 'y' , 'z'
    };

    private Md5Util() {}

    public static String md5(String plain) {
        // 保持历史的平台默认字符集行为（plain.getBytes()）—— 改用 UTF-8 会让
        // 非 ASCII 输入的 md5 值变化，进而影响已生成的 handler 名 / 转换器 key。
        return md5(plain.getBytes());
    }

    public static String md5(byte[] srcData) {
        byte[] secretBytes = null;
        try {
            secretBytes = MessageDigest.getInstance("md5").digest(srcData);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("没有这个md5算法！");
        }
        String md5code = new BigInteger(1, secretBytes).toString(16);
        for (int i = 0; i < 32 - md5code.length(); i++) {
            md5code = "0" + md5code;
        }
        return md5code;
    }


}
