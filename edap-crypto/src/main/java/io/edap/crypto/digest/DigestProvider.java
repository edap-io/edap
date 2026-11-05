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

package io.edap.crypto.digest;

import io.edap.crypto.CryptoKind;
import io.edap.crypto.CryptoProvider;

/**
 * 摘要（digest）提供者。
 *
 * <p>算法名用 JCE 规范名（{@code "SHA-256"} / {@code "SHA-1"} / {@code "MD5"}）。</p>
 */
public interface DigestProvider extends CryptoProvider {

    @Override
    default CryptoKind kind() {
        return CryptoKind.DIGEST;
    }

    /**
     * 创建一个与当前线程绑定的摘要会话。
     */
    DigestSession newSession();

    /**
     * 摘要长度（字节）；未知时返回 -1。
     */
    default int getDigestLength() {
        return -1;
    }
}
