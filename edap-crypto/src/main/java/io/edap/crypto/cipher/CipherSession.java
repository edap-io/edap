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

import java.security.GeneralSecurityException;

/**
 * 加/解密会话 —— 真正与线程绑定的单位。
 *
 * <p><b>线程绑定</b>：一个 session 不可跨线程使用。</p>
 *
 * <p><b>异常原样透出</b>：{@code doFinal} 声明 {@link GeneralSecurityException}，
 * JCE 的 {@code javax.crypto.AEADBadTagException}（认证 tag 校验失败 ⇒ 密文被篡改）
 * 原样抛给调用方，调用方据此把「密文被篡改」与「IO 错误」区分开。</p>
 */
public interface CipherSession extends AutoCloseable {

    /**
     * 加密/解密并结束。
     *
     * @throws GeneralSecurityException AEAD 认证失败时为 {@code AEADBadTagException}
     */
    byte[] doFinal(byte[] input) throws GeneralSecurityException;

    /**
     * 加密/解密并结束。
     *
     * @throws GeneralSecurityException AEAD 认证失败时为 {@code AEADBadTagException}
     */
    byte[] doFinal(byte[] input, int offset, int len) throws GeneralSecurityException;

    @Override
    default void close() {
        // 默认无资源需要释放
    }
}
