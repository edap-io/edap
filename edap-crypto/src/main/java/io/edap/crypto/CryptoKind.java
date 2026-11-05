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
 * 密码学原语类别。
 *
 * <p>四类的<b>生命周期模型完全不同</b>，所以 SPI 拆成四个独立接口而不是一个大接口：</p>
 * <ul>
 *   <li>{@link #DIGEST} —— 无状态、可重置、流式；</li>
 *   <li>{@link #MAC} —— 有 key、流式；</li>
 *   <li>{@link #CIPHER} —— 有 key + IV、一次性（有 tag）；</li>
 *   <li>{@link #RANDOM} —— 无状态。</li>
 * </ul>
 */
public enum CryptoKind {

    DIGEST,
    MAC,
    CIPHER,
    RANDOM
}
