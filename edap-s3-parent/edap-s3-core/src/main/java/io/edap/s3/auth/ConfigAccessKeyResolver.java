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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AccessKeyResolver} 默认实现 —— 从 {@link Properties} 加载 access key 配置。
 *
 * <p>配置格式(以 Properties 文本 / .properties 文件 / classpath resource 为输入):
 * <pre>
 * s3.accessKey.AKIAEXAMPLE.secret = wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY
 * s3.accessKey.AKIAEXAMPLE.region = us-east-1
 * s3.accessKey.AKIAEXAMPLE.buckets = bucket1, bucket2   # 留空 = 不限制
 * s3.accessKey.AKIAOTHER.secret   = ...
 * </pre>
 *
 * <p>key 前缀 {@code s3.accessKey.} + accessKeyId + {@code .secret / .region /
 * .buckets}。{@code region} 缺省 = {@code us-east-1}(S3 默认 region);
 * {@code buckets} 缺省 = null(不限制);{@code service} 写死 = {@code s3}。
 *
 * <p>线程安全:构造时一次性把所有 key 加载到 {@link ConcurrentHashMap},运行时
 * 只读。运行时不需要锁。
 */
public class ConfigAccessKeyResolver implements AccessKeyResolver {

    private static final String PREFIX = "s3.accessKey.";

    private final Map<String, ResolvedKey> keys;

    private ConfigAccessKeyResolver(Map<String, ResolvedKey> keys) {
        this.keys = keys;
    }

    public static ConfigAccessKeyResolver fromProperties(Properties props) {
        Map<String, ResolvedKey> map = new HashMap<>();
        Map<String, Map<String, String>> grouped = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(PREFIX)) continue;
            int dot = name.indexOf('.', PREFIX.length());
            if (dot < 0) continue;
            String accessKey = name.substring(PREFIX.length(), dot);
            String field = name.substring(dot + 1);
            String value = props.getProperty(name);
            grouped.computeIfAbsent(accessKey, k -> new HashMap<>()).put(field, value);
        }
        for (Map.Entry<String, Map<String, String>> e : grouped.entrySet()) {
            Map<String, String> v = e.getValue();
            String secret = v.get("secret");
            if (secret == null || secret.isEmpty()) {
                throw new IllegalArgumentException(
                        "access key '" + e.getKey() + "' missing required field 'secret'");
            }
            String region = v.getOrDefault("region", "us-east-1");
            String bucketsRaw = v.get("buckets");
            Set<String> buckets = null;
            if (bucketsRaw != null && !bucketsRaw.isEmpty()) {
                buckets = new HashSet<>();
                for (String b : bucketsRaw.split(",")) {
                    String t = b.trim();
                    if (!t.isEmpty()) buckets.add(t);
                }
            }
            map.put(e.getKey(), new ResolvedKey(secret, region, "s3", buckets));
        }
        return new ConfigAccessKeyResolver(map);
    }

    public static ConfigAccessKeyResolver fromPropertiesFile(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return fromProperties(p);
    }

    @Override
    public ResolvedKey resolve(String accessKeyId) {
        return keys.get(accessKeyId);
    }
}
