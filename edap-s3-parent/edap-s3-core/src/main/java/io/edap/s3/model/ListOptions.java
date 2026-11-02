/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.model;

/**
 * ListObjects 的查询参数 —— 把 prefix / delimiter / maxKeys / 分页 token
 * 收成一个不可变对象,让内存实现和磁盘实现共用同一套分页算法
 * ({@link io.edap.s3.store.ListPaging})。
 *
 * <p><b>maxKeys 的语义变更</b>:S3 规范里 {@code max-keys=0} 表示「返回 0 条」,
 * 而不是「不限」。要「不限」用常量 {@link #MAX_KEYS_UNLIMITED}。
 */
public final class ListOptions {

    /** 「不限」—— 内部 / 测试用。协议层永远用 0..1000。 */
    public static final int MAX_KEYS_UNLIMITED = Integer.MAX_VALUE;

    private final String prefix;             // never null
    private final String delimiter;          // never null
    private final int maxKeys;               // >= 0
    private final String continuationToken;  // nullable
    private final String startAfter;         // nullable

    public ListOptions(String prefix,
                       String delimiter,
                       int maxKeys,
                       String continuationToken,
                       String startAfter) {
        this.prefix = prefix == null ? "" : prefix;
        this.delimiter = delimiter == null ? "" : delimiter;
        this.maxKeys = Math.max(0, maxKeys);
        this.continuationToken = emptyToNull(continuationToken);
        this.startAfter = emptyToNull(startAfter);
    }

    public String prefix() {
        return prefix;
    }

    public String delimiter() {
        return delimiter;
    }

    public int maxKeys() {
        return maxKeys;
    }

    public String continuationToken() {
        return continuationToken;
    }

    public String startAfter() {
        return startAfter;
    }

    /** 便捷构造:无分页。 */
    public static ListOptions of(String prefix, String delimiter, int maxKeys) {
        return new ListOptions(prefix, delimiter, maxKeys, null, null);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
