/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.store;

import io.edap.s3.model.ListOptions;
import io.edap.s3.model.ListResult;
import io.edap.s3.model.ObjectMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.function.Function;

/**
 * ListObjects 的共享分页算法 —— 内存实现和磁盘实现走<b>同一份</b>代码,
 * 保证「同一个桶在两个后端上分页结果完全一致」。
 *
 * <p>前提:传入的 index 是<b>按键序排列</b>的有序 map(磁盘侧是
 * {@code ConcurrentSkipListMap<String,ObjectMeta>}，比较器 {@link Utf8KeyOrder}；
 * 内存侧同)。于是「先排序后截断」天然成立 —— 不再有「扫 → 截断 → 排序」导致
 * 第一页不是字典序第一页的老 bug。
 *
 * <p>算法：
 * <ol>
 *   <li>解析 continuation token ⇒ 边界 (kind, boundary)；没有 token 时用 start-after 定位。</li>
 *   <li>从 tailMap 起点开始遍历：prefix 过滤 → delimiter 折叠成 CommonPrefix →
 *       普通对象进 Contents。</li>
 *   <li>maxKeys <b>同时</b>计 Contents 和 CommonPrefixes（S3 规范）。</li>
 * </ol>
 *
 * <p><b>IsTruncated 判定</b>：页满即视为可能截断（不做「向前多看一个」）。
 * AWS 官方文档明确写了 "It is possible that you receive an empty list of results
 * even though there are more results to be returned" —— AWS 自己就这么干。
 * 精确判定在「最后一个结果是 CommonPrefix」时需要扫完整个 group，代价不可接受。
 * 客户端按标准流程多查一次空页即可。
 */
public final class ListPaging {

    private ListPaging() {}

    /**
     * @param index         已按键序排列的索引视图
     * @param options       查询参数
     * @param metaExtractor 从 map 的 value 里取 ObjectMeta（内存实现的 value 是
     *                      {@code StoredObject}，用它避免每次 LIST 分配 N 个包装对象）
     */
    public static ListResult paginate(NavigableMap<String, ?> index,
                                      ListOptions options,
                                      Function<String, ObjectMeta> metaExtractor) {
        final String prefix = options.prefix();
        final String delimiter = options.delimiter();
        final int maxKeys = options.maxKeys();

        if (maxKeys == 0) {
            // S3 规范:max-keys=0 ⇒ 返回空结果、IsTruncated=false
            return ListResult.empty();
        }

        ListToken.Kind kind = null;
        String boundary = null;
        String token = options.continuationToken();
        if (token != null) {
            ListToken.Decoded decoded = ListToken.decode(token, prefix, delimiter);
            kind = decoded.kind();
            boundary = decoded.boundary();
        }

        // ---- 定位起点 ----
        NavigableMap<String, ?> tail;
        if (kind != null) {
            tail = index.tailMap(boundary, false);          // 严格大于边界
        } else if (options.startAfter() != null) {
            tail = index.tailMap(options.startAfter(), false);
        } else if (!prefix.isEmpty()) {
            tail = index.tailMap(prefix, true);              // 跳过 prefix 之前的整段
        } else {
            tail = index;
        }

        List<ObjectMeta> contents = new ArrayList<>();
        List<String> commonPrefixes = new ArrayList<>();
        String lastGroup = null;                              // 上一个已计数的 group
        int count = 0;
        ListToken.Kind nextKind = null;
        String nextBoundary = null;
        boolean truncated = false;

        for (Map.Entry<String, ?> e : tail.entrySet()) {
            String key = e.getKey();

            // 3a. 上一页已经报过整个 group ⇒ 整段跳过
            if (kind == ListToken.Kind.COMMON_PREFIX && key.startsWith(boundary)) {
                continue;
            }

            // 3b. prefix 过滤(tailMap 只保证起点，prefix 之后的 key 未必匹配)
            if (!prefix.isEmpty() && !key.startsWith(prefix)) {
                continue;
            }

            // 3c. delimiter 折叠 → CommonPrefix
            if (!delimiter.isEmpty()) {
                int idx = key.indexOf(delimiter, prefix.length());
                if (idx >= 0) {
                    String group = key.substring(0, idx + delimiter.length());
                    if (group.equals(lastGroup)) {
                        continue;                              // 同一 group，已计数
                    }
                    commonPrefixes.add(group);
                    lastGroup = group;
                    count++;
                    nextKind = ListToken.Kind.COMMON_PREFIX;
                    nextBoundary = group;
                    if (count >= maxKeys) {
                        truncated = true;
                        break;
                    }
                    continue;
                }
            }

            // 3d. 普通对象
            contents.add(metaExtractor.apply(key));
            count++;
            nextKind = ListToken.Kind.KEY;
            nextBoundary = key;
            if (count >= maxKeys) {
                truncated = true;
                break;
            }
        }

        String nextToken = truncated && nextKind != null
                ? ListToken.encode(nextKind, nextBoundary, prefix, delimiter)
                : null;
        return new ListResult(contents, commonPrefixes, truncated, nextToken);
    }
}
