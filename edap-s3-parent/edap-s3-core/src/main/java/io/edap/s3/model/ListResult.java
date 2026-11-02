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

import java.util.Collections;
import java.util.List;

/**
 * ListObjects 的一页结果。
 *
 * <p>{@code contents} 与 {@code commonPrefixes} 都已按 key 字典序排列。
 * {@link #keyCount()} 是 S3 规范里的 KeyCount = contents + commonPrefixes
 * (两者共同消耗 max-keys 配额)。
 */
public final class ListResult {

    private final List<ObjectMeta> contents;
    private final List<String> commonPrefixes;
    private final boolean truncated;
    private final String nextContinuationToken;

    public ListResult(List<ObjectMeta> contents,
                      List<String> commonPrefixes,
                      boolean truncated,
                      String nextContinuationToken) {
        this.contents = contents == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(contents);
        this.commonPrefixes = commonPrefixes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(commonPrefixes);
        this.truncated = truncated;
        this.nextContinuationToken = nextContinuationToken;
    }

    public List<ObjectMeta> contents() {
        return contents;
    }

    public List<String> commonPrefixes() {
        return commonPrefixes;
    }

    /** 是否还有更多结果(下一页用 {@link #nextContinuationToken()} 续查)。 */
    public boolean truncated() {
        return truncated;
    }

    public String nextContinuationToken() {
        return nextContinuationToken;
    }

    /** S3 的 KeyCount = contents + commonPrefixes。 */
    public int keyCount() {
        return contents.size() + commonPrefixes.size();
    }

    public static ListResult empty() {
        return new ListResult(Collections.emptyList(), Collections.emptyList(), false, null);
    }
}
