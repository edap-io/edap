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

import java.util.Comparator;

/**
 * S3 key 的字典序 —— <b>UTF-8 字节序</b>。
 *
 * <p>Java 的 {@link String#compareTo} 比较的是 UTF-16 code unit,遇到代理对
 * (supplementary 字符)和 BMP 字符比较时与 UTF-8 字节序<b>不一致</b>:
 * <pre>
 *   "\uFFFD"(U+FFFD) vs "\uD83D\uDE00"(U+1F600)
 *   UTF-8 字节序 : EF BF BD &lt; F0 9F 98 80  ⇒ U+FFFD 在前
 *   String.compareTo: 0xFFFD &gt; 0xD83D         ⇒ U+1F600 在前  ❌
 * </pre>
 *
 * <p>UTF-8 编码保持 code point 序 ⇒ 直接按 code point 比较即可,且不需要分配
 * byte[]。
 */
public final class Utf8KeyOrder implements Comparator<String> {

    public static final Utf8KeyOrder INSTANCE = new Utf8KeyOrder();

    private Utf8KeyOrder() {}

    @Override
    public int compare(String a, String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int c1 = a.codePointAt(i);
            int c2 = b.codePointAt(j);
            if (c1 != c2) {
                return Integer.compare(c1, c2);
            }
            i += Character.charCount(c1);
            j += Character.charCount(c2);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
