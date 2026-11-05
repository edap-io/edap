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

import io.edap.crypto.CryptoKind;
import io.edap.crypto.CryptoProvider;

import java.security.GeneralSecurityException;

/**
 * 对称加密提供者。
 *
 * <p><b>v1 只支持一次性 {@code doFinal}</b>：AEAD 的流式需要额外管理 tag，
 * 成本高收益低。</p>
 *
 * <p><b>认证失败必须原样透出 {@code javax.crypto.AEADBadTagException}</b> ——
 * 调用方要能区分「密文被篡改」和「IO 错误」。</p>
 */
public interface CipherProvider extends CryptoProvider {

    @Override
    default CryptoKind kind() {
        return CryptoKind.CIPHER;
    }

    /**
     * JCE transformation（如 {@code "AES/GCM/NoPadding"}）。
     */
    String getTransformation();

    /**
     * key 长度（bit）。
     */
    int getKeyLength();

    /**
     * IV 长度（字节）。
     */
    int getIvLength();

    /**
     * 认证 tag 长度（bit）；非 AEAD 时返回 0。
     */
    int getTagLengthBits();

    /**
     * 是否是 AEAD（如 GCM）。
     */
    boolean isAead();

    /**
     * 创建一个加/解密会话。
     *
     * @param key    密钥（长度必须与 {@link #getKeyLength()} 匹配，否则抛
     *               {@code InvalidKeyException}）
     * @param iv     初始向量
     * @param opmode {@code Cipher.ENCRYPT_MODE} / {@code Cipher.DECRYPT_MODE}
     * @throws GeneralSecurityException key/iv 非法或算法不可用
     */
    CipherSession newSession(byte[] key, byte[] iv, int opmode) throws GeneralSecurityException;
}
