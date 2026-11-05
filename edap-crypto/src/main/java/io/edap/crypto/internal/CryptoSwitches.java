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

package io.edap.crypto.internal;

import io.edap.crypto.CryptoKind;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密码学开关（三段式）。
 *
 * <table>
 *   <caption>开关</caption>
 *   <tr><th>属性</th><th>默认</th><th>语义</th></tr>
 *   <tr><td>{@code edap.crypto.native}</td><td>true</td><td>总闸。false ⇒ 全部走 JCE</td></tr>
 *   <tr><td>{@code edap.crypto.digest.native}</td><td>true</td><td>digest 分项闸</td></tr>
 *   <tr><td>{@code edap.crypto.mac.native}</td><td>true</td><td>mac 分项闸</td></tr>
 *   <tr><td>{@code edap.crypto.cipher.native}</td><td>true</td><td>cipher 分项闸</td></tr>
 *   <tr><td>{@code edap.crypto.random.native}</td><td>true</td><td>random 分项闸</td></tr>
 *   <tr><td>{@code edap.crypto.strict}</td><td>false</td><td>严格模式：native 不可用 ⇒ 抛异常而非静默降级</td></tr>
 *   <tr><td>{@code edap.crypto.<kind>.provider}</td><td>未设</td><td>强制 {@code native} / {@code jce}</td></tr>
 * </table>
 *
 * <p><b>降级语义</b>：默认静默降级到 JCE，但会打<b>一次</b> WARN（按 key 去重）保证可观测。
 * 现有 JWT 的 native 探测是完全静默的（失败无日志），这是本模块要改进的点。</p>
 *
 * <p><b>★ 什么时候该关掉 native（实测结论，见 edap-crypto/doc/DESIGN.md §10.3）</b>：
 * native <b>不是</b>无条件更快。JNI 调用 + 字节拷贝是与 payload 无关的固定开销，
 * 所以「小 payload + 高并发」场景 native 会输给 JCE：</p>
 * <ul>
 *   <li>大 payload（≥8KB）：native 快 <b>2.7x ~ 9.6x</b> ⇒ 保持 native；</li>
 *   <li>小 payload（64B）单线程：digest 亏、mac 赚 ⇒ 看算法；</li>
 *   <li>小 payload + 高并发（16 线程）：最差只有 JCE 的 <b>0.24x</b>
 *       ⇒ 业务形态是「大量小包 + 高并发」时应设 {@code edap.crypto.native=false}。</li>
 * </ul>
 */
public final class CryptoSwitches {

    /** 总闸 */
    public static final String PROP_GLOBAL_NATIVE = "edap.crypto.native";

    /** 严格模式 */
    public static final String PROP_STRICT = "edap.crypto.strict";

    /**
     * 已经打过 WARN 的 key —— 保证同一个降级原因只告警一次。
     */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private CryptoSwitches() {
    }

    /**
     * 分项属性名：{@code edap.crypto.<kind>.native}。
     */
    public static String nativeProperty(CryptoKind kind) {
        return "edap.crypto." + kind.name().toLowerCase(java.util.Locale.ENGLISH) + ".native";
    }

    /**
     * 分项属性名：{@code edap.crypto.<kind>.provider}。
     */
    public static String providerProperty(CryptoKind kind) {
        return "edap.crypto." + kind.name().toLowerCase(java.util.Locale.ENGLISH) + ".provider";
    }

    /**
     * 某一类原语是否允许走 native。
     *
     * <p>总闸优先：{@code edap.crypto.native=false} ⇒ 全部返回 false。</p>
     */
    public static boolean nativeEnabled(CryptoKind kind) {
        if (!bool(System.getProperty(PROP_GLOBAL_NATIVE), true)) {
            return false;
        }
        return bool(System.getProperty(nativeProperty(kind)), true);
    }

    /**
     * 是否强制指定实现。
     *
     * @return {@code "native"} / {@code "jce"}；未设置时返回 null
     */
    public static String forcedProvider(CryptoKind kind) {
        String v = System.getProperty(providerProperty(kind));
        if (v == null) {
            return null;
        }
        v = v.trim();
        if (v.isEmpty()) {
            return null;
        }
        if ("native".equalsIgnoreCase(v) || "jce".equalsIgnoreCase(v)) {
            return v.toLowerCase(java.util.Locale.ENGLISH);
        }
        return null;
    }

    /**
     * 严格模式：native 不可用时抛异常而非静默降级。
     */
    public static boolean isStrict() {
        return bool(System.getProperty(PROP_STRICT), false);
    }

    /**
     * 打一次 WARN（按 key 去重）。
     *
     * <p>出口是 {@link CryptoDiagnostics}（默认 stderr），<b>不是</b> edap-log 的 logger ——
     * 原因见 {@link CryptoDiagnostics} 的类注释（类初始化环）。</p>
     */
    public static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            CryptoDiagnostics.warn(message);
        }
    }

    /**
     * 清空告警去重集合 —— <b>仅供测试</b>。
     */
    public static void resetWarningsForTest() {
        WARNED.clear();
    }

    /**
     * 解析布尔系统属性。
     *
     * <p>接受 {@code true/false}（大小写不敏感）与 {@code on/off}、{@code yes/no}、
     * {@code enable/disable}。</p>
     */
    public static boolean bool(String value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v)
                || "enable".equalsIgnoreCase(v) || "enabled".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v)
                || "disable".equalsIgnoreCase(v) || "disabled".equalsIgnoreCase(v)) {
            return false;
        }
        return defaultValue;
    }
}
