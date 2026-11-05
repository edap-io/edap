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

/**
 * HPACK 静态表（RFC 7541 Appendix A），共 61 项，索引 1..61。
 *
 * <p>静态表在所有连接之间共享且永不变化，所以索引 0 留空。</p>
 */
public final class HpackStaticTable {

    /** 静态表条目数 */
    public static final int SIZE = 61;

    /** 下标即静态表索引（1..61），0 号不用 */
    private static final String[] NAMES = {
            null,
            ":authority",
            ":method", ":method",
            ":path", ":path",
            ":scheme", ":scheme",
            ":status", ":status", ":status", ":status", ":status", ":status", ":status",
            "accept-charset",
            "accept-encoding",
            "accept-language",
            "accept-ranges",
            "accept",
            "access-control-allow-origin",
            "age",
            "allow",
            "authorization",
            "cache-control",
            "content-disposition",
            "content-encoding",
            "content-language",
            "content-length",
            "content-location",
            "content-range",
            "content-type",
            "cookie",
            "date",
            "etag",
            "expect",
            "expires",
            "from",
            "host",
            "if-match",
            "if-modified-since",
            "if-none-match",
            "if-range",
            "if-unmodified-since",
            "last-modified",
            "link",
            "location",
            "max-forwards",
            "proxy-authenticate",
            "proxy-authorization",
            "range",
            "referer",
            "refresh",
            "retry-after",
            "server",
            "set-cookie",
            "strict-transport-security",
            "transfer-encoding",
            "user-agent",
            "vary",
            "via",
            "www-authenticate"
    };

    private static final String[] VALUES = {
            null,
            "",
            "GET", "POST",
            "/", "/index.html",
            "http", "https",
            "200", "204", "206", "304", "400", "404", "500",
            "",
            "gzip, deflate",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            ""
    };

    private HpackStaticTable() {
    }

    /**
     * 取静态表条目的名称。index 必须是 1..61。
     */
    public static String getName(int index) {
        if (index < 1 || index > SIZE) {
            throw new IndexOutOfBoundsException("静态表索引越界: " + index);
        }
        return NAMES[index];
    }

    /**
     * 取静态表条目的值。index 必须是 1..61。
     */
    public static String getValue(int index) {
        if (index < 1 || index > SIZE) {
            throw new IndexOutOfBoundsException("静态表索引越界: " + index);
        }
        return VALUES[index];
    }

    /**
     * 找 name + value 完全匹配的静态表索引，找不到返回 0。
     */
    public static int find(String name, String value) {
        for (int i = 1; i <= SIZE; i++) {
            if (NAMES[i].equals(name) && VALUES[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 找 name 匹配的静态表索引（取第一个），找不到返回 0。
     */
    public static int findName(String name) {
        for (int i = 1; i <= SIZE; i++) {
            if (NAMES[i].equals(name)) {
                return i;
            }
        }
        return 0;
    }
}
