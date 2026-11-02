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

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.ListOptions;
import io.edap.s3.model.ListResult;
import io.edap.s3.model.ObjectMeta;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ListPaging} 共享分页算法 —— 重点覆盖两个容易出错的边界:
 * <ul>
 *   <li>续查时<b>不重复</b>输出同一个 CommonPrefix(上一整组已报过)</li>
 *   <li>Contents 里有一个 key <b>正好是某个 group 的前缀</b>时(group 不丢)</li>
 * </ul>
 */
public class ListPagingTest {

    private static NavigableMap<String, ObjectMeta> index(String... keys) {
        NavigableMap<String, ObjectMeta> m = new ConcurrentSkipListMap<>(Utf8KeyOrder.INSTANCE);
        Instant lm = Instant.parse("2026-09-06T10:30:00Z");
        for (String k : keys) {
            m.put(k, new ObjectMeta(k, 1, "etag", "text/plain", lm, Collections.emptyMap()));
        }
        return m;
    }

    private static ListResult page(NavigableMap<String, ObjectMeta> m, ListOptions o) {
        return ListPaging.paginate(m, o, m::get);
    }

    private static List<String> keysOf(List<ObjectMeta> metas) {
        List<String> out = new ArrayList<>();
        for (ObjectMeta m : metas) {
            out.add(m.key());
        }
        return out;
    }

    // ================= 基础 =================

    @Test
    void returnsSortedContents() {
        ListResult r = page(index("c", "a", "b"), ListOptions.of("", "", 1000));
        assertEquals(List.of("a", "b", "c"), keysOf(r.contents()));
        assertTrue(r.commonPrefixes().isEmpty());
        assertFalse(r.truncated());
        assertNull(r.nextContinuationToken());
        assertEquals(3, r.keyCount());
    }

    @Test
    void maxKeysZeroReturnsEmptyNotTruncated() {
        ListResult r = page(index("a", "b"), ListOptions.of("", "", 0));
        assertTrue(r.contents().isEmpty());
        assertFalse(r.truncated());
        assertNull(r.nextContinuationToken());
    }

    @Test
    void prefixFiltersAndKeepsKeyEqualToPrefix() {
        NavigableMap<String, ObjectMeta> m = index("a", "ab", "b");
        ListResult r = page(m, ListOptions.of("a", "", 1000));
        // "a" 自身匹配 prefix,"ab" 也匹配,"b" 不匹配
        assertEquals(List.of("a", "ab"), keysOf(r.contents()));
    }

    @Test
    void startAfterSkipsEarlierKeys() {
        NavigableMap<String, ObjectMeta> m = index("a", "b", "c", "d");
        ListResult r = page(m, new ListOptions("", "", 1000, null, "b"));
        assertEquals(List.of("c", "d"), keysOf(r.contents()));
    }

    @Test
    void startAfterIgnoredWhenContinuationTokenPresent() {
        NavigableMap<String, ObjectMeta> m = index("a", "b", "c", "d");
        ListResult p1 = page(m, ListOptions.of("", "", 2));
        ListResult p2 = page(m, new ListOptions("", "", 1000, p1.nextContinuationToken(), "c"));
        assertEquals(List.of("c", "d"), keysOf(p2.contents()));
    }

    // ================= delimiter =================

    @Test
    void delimiterAggregatesCommonPrefixes() {
        NavigableMap<String, ObjectMeta> m = index(
                "docs/a.txt", "docs/b.txt", "images/x.png", "top.txt");
        ListResult r = page(m, ListOptions.of("", "/", 1000));
        assertEquals(List.of("top.txt"), keysOf(r.contents()));
        assertEquals(List.of("docs/", "images/"), r.commonPrefixes());
        assertEquals(3, r.keyCount());
    }

    @Test
    void maxKeysCountsContentsAndCommonPrefixesTogether() {
        NavigableMap<String, ObjectMeta> m = index(
                "docs/a.txt", "docs/b.txt", "images/x.png", "top.txt");
        // max-keys=2 ⇒ 总共只能出 2 项:docs/ + images/(两个 commonPrefix 一起占配额)
        ListResult r = page(m, ListOptions.of("", "/", 2));
        assertEquals(2, r.keyCount());
        assertTrue(r.contents().isEmpty());
        assertEquals(List.of("docs/", "images/"), r.commonPrefixes());
        assertTrue(r.truncated());

        // max-keys=3 ⇒ 2 个 commonPrefix + 1 个 content
        ListResult r3 = page(m, ListOptions.of("", "/", 3));
        assertEquals(3, r3.keyCount());
        assertEquals(List.of("top.txt"), keysOf(r3.contents()));
        assertEquals(List.of("docs/", "images/"), r3.commonPrefixes());
        assertTrue(r3.truncated());
    }

    // ================= 边界 ①:group 不重复 =================

    @Test
    void paginationDoesNotRepeatCommonPrefix() {
        // a/1 a/2 折成一个 group "a/";若续查只按"最后一个 key"定位,
        // 第二页会再次输出 "a/" ⇒ 重复。
        NavigableMap<String, ObjectMeta> m = index("a/1", "a/2", "b", "c");

        ListResult p1 = page(m, ListOptions.of("", "/", 1));
        assertEquals(List.of("a/"), p1.commonPrefixes());
        assertTrue(p1.contents().isEmpty());
        assertTrue(p1.truncated());
        assertNotNull(p1.nextContinuationToken());

        ListResult p2 = page(m, new ListOptions("", "/", 1000, p1.nextContinuationToken(), null));
        assertTrue(p2.commonPrefixes().isEmpty());
        assertEquals(List.of("b", "c"), keysOf(p2.contents()));
        assertFalse(p2.truncated());
    }

    @Test
    void paginationAcrossPagesYieldsEveryKeyExactlyOnce() {
        NavigableMap<String, ObjectMeta> m = index(
                "a", "a/1", "a/2", "b", "b/1", "c");
        Set<String> seenContents = new LinkedHashSet<>();
        Set<String> seenPrefixes = new LinkedHashSet<>();
        String token = null;
        int pages = 0;
        while (true) {
            ListResult r = page(m, new ListOptions("", "/", 2, token, null));
            pages++;
            for (ObjectMeta meta : r.contents()) {
                assertTrue(seenContents.add(meta.key()),
                        "duplicate content key across pages: " + meta.key());
            }
            for (String cp : r.commonPrefixes()) {
                assertTrue(seenPrefixes.add(cp),
                        "duplicate common prefix across pages: " + cp);
            }
            if (!r.truncated() || r.nextContinuationToken() == null) break;
            token = r.nextContinuationToken();
            assertTrue(pages < 20, "pagination did not terminate");
        }
        assertEquals(Set.of("a", "b", "c"), seenContents);
        assertEquals(Set.of("a/", "b/"), seenPrefixes);
    }

    // ================= 边界 ②:Contents key 是 group 前缀时不漏 =================

    @Test
    void contentKeyThatIsPrefixOfGroupIsNotLost() {
        // key "a" 与 group "a/" 共存。若续查一律排除 startsWith(boundary),
        // 第 2 页会把 "a" 漏掉。
        NavigableMap<String, ObjectMeta> m = index("a", "a/1", "b");

        ListResult p1 = page(m, ListOptions.of("", "/", 1));
        assertEquals(List.of("a"), keysOf(p1.contents()));
        assertTrue(p1.commonPrefixes().isEmpty());
        assertTrue(p1.truncated());

        ListResult p2 = page(m, new ListOptions("", "/", 1000, p1.nextContinuationToken(), null));
        assertEquals(List.of("b"), keysOf(p2.contents()));
        assertEquals(List.of("a/"), p2.commonPrefixes());
    }

    // ================= token =================

    @Test
    void tokenFromDifferentPrefixIsRejected() {
        NavigableMap<String, ObjectMeta> m = index("a", "b", "c");
        ListResult p1 = page(m, ListOptions.of("", "", 1));
        S3Exception ex = assertThrows(S3Exception.class,
                () -> page(m, new ListOptions("x/", "", 1000, p1.nextContinuationToken(), null)));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex.code());
    }

    @Test
    void malformedTokenIsRejected() {
        NavigableMap<String, ObjectMeta> m = index("a");
        assertThrows(S3Exception.class,
                () -> page(m, new ListOptions("", "", 1000, "not-base64!!", null)));
        assertThrows(S3Exception.class,
                () -> page(m, new ListOptions("", "", 1000, "AAAA", null)));
    }

    @Test
    void tokenRoundTripsBoundaryAndKind() {
        String t = ListToken.encode(ListToken.Kind.COMMON_PREFIX, "a/", "p", "/");
        ListToken.Decoded d = ListToken.decode(t, "p", "/");
        assertEquals(ListToken.Kind.COMMON_PREFIX, d.kind());
        assertEquals("a/", d.boundary());
    }

    // ================= 排序 =================

    @Test
    void utf8ByteOrderPutsReplacementCharBeforeSupplementary() {
        // UTF-8 字节序:EF BF BD < F0 9F 98 80
        // String.compareTo 会反过来(代理对 0xD83D < 0xFFFD)
        NavigableMap<String, ObjectMeta> m =
                index("\uD83D\uDE00", "�");
        assertEquals(List.of("�", "\uD83D\uDE00"), keysOf(page(m, ListOptions.of("", "", 1000)).contents()));
        assertTrue("�".compareTo("\uD83D\uDE00") > 0, "前提:String.compareTo 与字节序相反");
    }
}
