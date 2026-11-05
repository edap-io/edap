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

package io.edap.crypto.random;

import io.edap.crypto.CryptoKind;
import io.edap.crypto.CryptoProvider;

/**
 * 随机数提供者。
 */
public interface RandomProvider extends CryptoProvider {

    @Override
    default CryptoKind kind() {
        return CryptoKind.RANDOM;
    }

    /**
     * 填满整个缓冲区。
     */
    void nextBytes(byte[] buf);

    /**
     * 填充缓冲区的一段。
     */
    void nextBytes(byte[] buf, int offset, int len);
}
