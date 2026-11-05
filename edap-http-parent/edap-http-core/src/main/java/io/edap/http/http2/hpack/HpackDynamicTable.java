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

package io.edap.http.http2.hpack;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * HPACK 动态表（RFC 7541 §2.3.2 / §4）。
 *
 * <p>条目按加入顺序排列，<b>索引 1 是最新加入的</b>（RFC 7541 §2.3.3：动态表第一个
 * 条目的 HPACK 索引是 62）。</p>
 *
 * <p>条目大小 = {@code name.length + value.length + 32}，超出上限时从最旧开始驱逐。
 * 新条目本身就超过上限时整表清空（RFC 7541 §4.4）。</p>
 */
public class HpackDynamicTable {

    public static final int DEFAULT_MAX_SIZE = 4096;
    /** RFC 7541 §4.1：每条目固定开销 32 字节 */
    public static final int ENTRY_OVERHEAD = 32;

    /** 队头最旧，队尾最新 */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    private int size;
    private int maxSize = DEFAULT_MAX_SIZE;

    /**
     * 设置动态表上限并驱逐超限条目。
     *
     * <p>把上限设成 0 就等价于「清空动态表」（RFC 7541 §6.3）。</p>
     */
    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        evict();
    }

    public int getMaxSize() {
        return maxSize;
    }

    /** 当前动态表占用的字节数 */
    public int getSize() {
        return size;
    }

    /** 当前动态表条目数 */
    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 加入一条新记录。
     */
    public void add(String name, String value) {
        int entrySize = name.length() + value.length() + ENTRY_OVERHEAD;
        if (entrySize > maxSize) {
            // RFC 7541 §4.4：条目比表还大 ⇒ 表被清空，新条目不入表
            entries.clear();
            size = 0;
            return;
        }
        entries.addLast(new Entry(name, value, entrySize));
        size += entrySize;
        evict();
    }

    private void evict() {
        while (size > maxSize) {
            Entry e = entries.pollFirst();
            if (e == null) {
                break;
            }
            size -= e.size;
        }
    }

    /**
     * 按 HPACK 动态表索引取条目：<b>1 = 最新加入的</b>。
     *
     * @param index 1..条目数
     */
    public Entry get(int index) {
        if (index < 1 || index > entries.size()) {
            return null;
        }
        Iterator<Entry> it = entries.descendingIterator();
        int i = 1;
        while (it.hasNext()) {
            Entry e = it.next();
            if (i++ == index) {
                return e;
            }
        }
        return null;
    }

    /**
     * 动态表条目。
     */
    public static class Entry {
        private final String name;
        private final String value;
        private final int    size;

        Entry(String name, String value, int size) {
            this.name = name;
            this.value = value;
            this.size = size;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public int getSize() {
            return size;
        }
    }
}
