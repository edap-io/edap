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
 * 密码学实现的公共父接口。
 *
 * <p>实现分两类：JCE（JDK 内置）与 native（edap-native JNI）。
 * 两者通过 {@link CryptoRegistry} 注册与查找，调用方只认本接口。</p>
 *
 * <p><b>线程安全</b>：provider 本身是<b>全局单例语义</b>，但 JCE 的
 * {@code MessageDigest} / {@code Mac} / {@code Cipher} 三者都<b>非</b>线程安全，
 * 所以 provider 内部必须用 {@code ThreadLocal} 复用它们；
 * 真正与线程绑定的单位是 <b>session</b>。</p>
 */
public interface CryptoProvider {

    /**
     * 原语类别。
     */
    CryptoKind kind();

    /**
     * 算法名（JCE 规范名，如 {@code "SHA-256"} / {@code "HmacSHA256"} /
     * {@code "AES/GCM/NoPadding"}）。
     */
    String algorithm();
}
