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

package io.edap.json.writer;

/**
 * JsonWriter的基类，封装JsonWriter常用操作
 */
public abstract class AbstractJsonWriter {

    public static final boolean[] CAN_DIRECT_WRITE = new boolean[128];
    /**
     * JSON需要转义的字符的数组，第一维是char，二维是该char需要被转义成的byte数组
     */
    public static final byte[][] REPLACEMENT_CHARS;
    /**
     * HTML安全的需要转义的字符数组，第一维是char 第二维度为char需要转义成的byte数组
     */
    public static final byte[][] HTML_REPLACEMENT_CHARS;
    /**
     * javascript规定的特殊字符,2028,2029
     */
    public static final byte[] JS_REPLACEMENT_CHAR;

    static {

        for (int i = 0; i < CAN_DIRECT_WRITE.length; i++) {
            if (i > 31 && i < 126 && i != '"' && i != '\\') {
                CAN_DIRECT_WRITE[i] = true;
            }
        }

        JS_REPLACEMENT_CHAR = new byte[12];
        System.arraycopy("\\u2028".getBytes(), 0, JS_REPLACEMENT_CHAR, 0, 6);
        System.arraycopy("\\u2028".getBytes(), 0, JS_REPLACEMENT_CHAR, 6, 6);

        REPLACEMENT_CHARS = new byte[128][];
        String hex;
        for (int i = 0; i <= 0x1f; i++) {
            hex = String.format("\\u%04x", (int) i);
            REPLACEMENT_CHARS[i] = hex.getBytes();
        }

        REPLACEMENT_CHARS['"']  = "\\\"".getBytes();
        REPLACEMENT_CHARS['\\'] = "\\\\".getBytes();
        REPLACEMENT_CHARS['\t'] = "\\t".getBytes();
        REPLACEMENT_CHARS['\b'] = "\\b".getBytes();
        REPLACEMENT_CHARS['\n'] = "\\n".getBytes();
        REPLACEMENT_CHARS['\r'] = "\\r".getBytes();
        REPLACEMENT_CHARS['\f'] = "\\f".getBytes();

        HTML_REPLACEMENT_CHARS = REPLACEMENT_CHARS.clone();
        HTML_REPLACEMENT_CHARS['<']  = "\\u003c".getBytes();
        HTML_REPLACEMENT_CHARS['>']  = "\\u003e".getBytes();
        HTML_REPLACEMENT_CHARS['&']  = "\\u0026".getBytes();
        HTML_REPLACEMENT_CHARS['=']  = "\\u003d".getBytes();
        HTML_REPLACEMENT_CHARS['\''] = "\\u0027".getBytes();
    }
}
