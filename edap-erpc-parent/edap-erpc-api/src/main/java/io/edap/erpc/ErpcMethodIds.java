/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * methodId 的分配规则。
 *
 * <p>★ <b>核心约束：methodId 必须是纯函数</b> —— 客户端与服务端各自算，算出来必须一样。
 * 所以不能「谁先注册谁先拿号」（注册顺序在两边不一致）。三种策略：</p>
 *
 * <table border="1">
 *   <caption>三种分配策略</caption>
 *   <tr><th>策略</th><th>规则</th><th>跨节点一致</th></tr>
 *   <tr><td>{@code hash}（默认）</td>
 *       <td>FNV-1a 64 位算 {@code serviceName + "." + methodName}，混入 seed，折叠到 {@code [1, 0x7FFFFFFF]}</td>
 *       <td>✅ 纯函数</td></tr>
 *   <tr><td>{@code increment}</td>
 *       <td>从 seed（默认 10000）开始递增，<b>按方法键字典序</b>分配</td>
 *       <td>✅ 只要排序一致就一致</td></tr>
 *   <tr><td>{@code fixed}</td>
 *       <td>所有方法都用 seed 作为 id</td>
 *       <td>⚠ 只适合单方法服务</td></tr>
 * </table>
 *
 * <p>★ **31 位空间下 10 万个方法的期望碰撞数 ≈ 2.3** ⇒ 无论哪种策略，容器在部署期
 * 都要做 methodId 冲突检测（见容器的路由表合并），撞了就 fail-fast。</p>
 */
public final class ErpcMethodIds {

    /** 默认种子：{@code increment} 的起始号 / {@code fixed} 的唯一号 / {@code hash} 的混入值。 */
    public static final long DEFAULT_SEED = 10000L;

    /** methodId 的取值上界（含）。 */
    public static final int MAX_ID = 0x7FFFFFFF;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME        = 0x100000001b3L;

    private ErpcMethodIds() {
    }

    /**
     * methodId 分配策略。
     */
    public enum Strategy {
        /** FNV-1a 哈希（默认）。 */
        HASH,
        /** 按字典序递增。 */
        INCREMENT,
        /** 全部方法同一个号。 */
        FIXED;

        /**
         * 按配置名解析（大小写不敏感）。
         *
         * @param name 配置名；null 或空 ⇒ {@link #HASH}
         */
        public static Strategy of(String name) {
            if (name == null || name.isEmpty()) {
                return HASH;
            }
            switch (name.trim().toLowerCase(java.util.Locale.ENGLISH)) {
                case "hash":
                    return HASH;
                case "increment":
                    return INCREMENT;
                case "fixed":
                    return FIXED;
                default:
                    throw new IllegalArgumentException("未知的 methodId 分配策略: " + name);
            }
        }
    }

    /**
     * 方法唯一键：{@code serviceName + "." + methodName}。
     *
     * <p>★ serviceName 取 proto 的服务名（{@code @ProtoService.serviceName()}），
     * **不能从 java 包名反推** —— proto 的 {@code java_package} 可以覆盖 proto package。</p>
     */
    public static String key(String serviceName, String methodName) {
        return (serviceName == null ? "" : serviceName) + "." + (methodName == null ? "" : methodName);
    }

    /**
     * {@code hash} 策略：算单个方法的 methodId。
     *
     * @param seed 混入值
     * @return {@code [1, 0x7FFFFFFF]} 区间内的号
     */
    public static int hash(String serviceName, String methodName, long seed) {
        return hashKey(key(serviceName, methodName), seed);
    }

    /**
     * {@code hash} 策略：按方法键算 methodId。
     */
    public static int hashKey(String methodKey, long seed) {
        long h = fnv1a64(methodKey == null ? "" : methodKey);
        // 混入 seed：同一批方法换一个 seed 就是另一套号（灰度/多环境共存用）
        h ^= seed * FNV_PRIME;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        int id = (int) (h & 0x7FFFFFFFL);
        return id == 0 ? 1 : id;
    }

    /**
     * {@code fixed} 策略：所有方法用同一个号。
     */
    public static int fixed(long seed) {
        long id = seed & 0x7FFFFFFFL;
        return id == 0 ? 1 : (int) id;
    }

    /**
     * {@code increment} 策略：给一批方法按<b>字典序</b>分配递增的号。
     *
     * @param methodKeys 方法键集合（{@link #key(String, String)} 的结果）
     * @param seed       起始号
     * @return 方法键 → methodId
     */
    public static Map<String, Integer> increment(Collection<String> methodKeys, long seed) {
        Map<String, Integer> out = new HashMap<>();
        // TreeSet ⇒ 字典序；去重
        TreeSet<String> sorted = new TreeSet<>(methodKeys);
        long next = seed & 0x7FFFFFFFL;
        if (next == 0) {
            next = 1;
        }
        for (String k : sorted) {
            out.put(k, (int) next);
            next++;
            if (next > MAX_ID) {
                throw new IllegalStateException("methodId 超出上限: " + MAX_ID);
            }
        }
        return out;
    }

    /**
     * 按策略给一批方法分配 methodId。
     *
     * @param strategy   分配策略
     * @param methodKeys 方法键集合
     * @param seed       种子
     * @return 方法键 → methodId
     */
    public static Map<String, Integer> assignAll(Strategy strategy, Collection<String> methodKeys, long seed) {
        if (strategy == null) {
            strategy = Strategy.HASH;
        }
        switch (strategy) {
            case INCREMENT:
                return increment(methodKeys, seed);
            case FIXED: {
                Map<String, Integer> out = new HashMap<>();
                int id = fixed(seed);
                for (String k : methodKeys) {
                    out.put(k, id);
                }
                return out;
            }
            case HASH:
            default: {
                Map<String, Integer> out = new HashMap<>();
                for (String k : methodKeys) {
                    out.put(k, hashKey(k, seed));
                }
                return out;
            }
        }
    }

    private static long fnv1a64(String s) {
        long h = FNV_OFFSET_BASIS;
        for (int i = 0; i < s.length(); i++) {
            h ^= (s.charAt(i) & 0xff);
            h *= FNV_PRIME;
            h ^= ((s.charAt(i) >>> 8) & 0xff);
            h *= FNV_PRIME;
        }
        return h;
    }
}
