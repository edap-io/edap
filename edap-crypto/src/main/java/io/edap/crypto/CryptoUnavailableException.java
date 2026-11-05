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

package io.edap.crypto;

/**
 * 指定的实现不可用。
 *
 * <p>抛出时机：</p>
 * <ul>
 *   <li>显式要求 native（{@code edap.crypto.<kind>.provider=native}）但 native 不可用；</li>
 *   <li>严格模式（{@code edap.crypto.strict=true}）下 native 不可用。</li>
 * </ul>
 */
public class CryptoUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CryptoUnavailableException(String message) {
        super(message);
    }

    public CryptoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
