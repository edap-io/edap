/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.auth;

import java.util.Set;

/**
 * Access key 解析 SPI —— 根据 access key id 查出 secret key + 权限信息。
 *
 * <p>由 {@link SigV4Verifier} 在验签时调用:
 * <ul>
 *   <li>返回 null → 该 access key 不存在 → 抛 INVALID_ACCESS_KEY_ID</li>
 *   <li>返回 ResolvedKey → 用 secretKey 重算签名 + 用 region/service 做 scope 检查
 *       + 用 allowedBuckets 做桶级 ACL 检查</li>
 * </ul>
 *
 * <p>设计成 SPI 而不是单例:Phase 1 默认实现从 Properties 读;
 * 业务方可以注入 LDAP / 数据库 / IAM 等后端的实现。
 */
public interface AccessKeyResolver {

    ResolvedKey resolve(String accessKeyId);

    /**
     * 解析后的 access key 信息 —— immutable。
     *
     * <p>{@link #allowedBuckets}:
     * <ul>
     *   <li>null —— 不限制,该 key 可访问任何桶</li>
     *   <li>非空 Set —— 该 key 只能访问这些桶(其他桶请求 → ACCESS_DENIED)</li>
     * </ul>
     */
    final class ResolvedKey {
        private final String secretKey;
        private final String region;
        private final String service;
        private final Set<String> allowedBuckets;

        public ResolvedKey(String secretKey, String region, String service, Set<String> allowedBuckets) {
            this.secretKey = secretKey;
            this.region = region;
            this.service = service;
            this.allowedBuckets = allowedBuckets;
        }

        public String secretKey() { return secretKey; }
        public String region() { return region; }
        public String service() { return service; }
        public Set<String> allowedBuckets() { return allowedBuckets; }
    }
}
