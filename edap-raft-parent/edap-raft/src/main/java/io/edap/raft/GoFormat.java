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

package io.edap.raft;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Go 风格的格式化，等价于 Go 的 {@code fmt.Sprintf}。
 *
 * <p>etcd raft 的日志全部用 Go 的格式串写成（{@code "%x became leader at term %d"}），
 * Java 侧要逐字复刻这些日志，就需要一个能理解 Go 格式动词的渲染器。
 * 这里只实现 raft 用得到的几个动词：</p>
 *
 * <ul>
 *     <li>{@code %s}：字符串；{@code byte[]} 按原始字节输出</li>
 *     <li>{@code %d}：十进制整数</li>
 *     <li>{@code %x}：十六进制；{@code byte[]} 逐字节转成两位十六进制</li>
 *     <li>{@code %v}：默认格式，等价于对象的 {@code toString()}</li>
 *     <li>{@code %q}：带双引号的字符串字面量，转义规则同 Go</li>
 *     <li>{@code %%}：字面量 {@code %}</li>
 * </ul>
 *
 * <p>Go 里 {@code %x} 作用在整数上得到的是不带前缀、不补零的小写十六进制，
 * 这一点在节点 id 的打印上很关键：id 为 10 时打印成 {@code a} 而不是 {@code 0a}。</p>
 */
public final class GoFormat {

    private GoFormat() {
    }

    /**
     * 按 Go 的格式串渲染参数。
     *
     * @param format 格式串，可以为 null（视为没有占位符）
     * @param args   参数，依次对应格式串里的占位符
     * @return 渲染后的字符串
     */
    public static String format(String format, Object... args) {
        if (format == null) {
            return "";
        }
        if (args == null || args.length == 0) {
            // 没有参数时直接返回，省掉一次扫描
            return format;
        }
        StringBuilder buf = new StringBuilder(format.length() + 32);
        int argIndex = 0;
        int i = 0;
        int len = format.length();
        while (i < len) {
            char c = format.charAt(i);
            if (c != '%') {
                buf.append(c);
                i++;
                continue;
            }
            if (i + 1 >= len) {
                throw new RaftException("incomplete format specifier at end of format string");
            }
            char verb = format.charAt(i + 1);
            if (verb == '%') {
                buf.append('%');
                i += 2;
                continue;
            }
            if (argIndex >= args.length) {
                throw new RaftException("format string has more verbs than arguments: " + format);
            }
            buf.append(render(verb, args[argIndex++]));
            i += 2;
        }
        if (argIndex < args.length) {
            throw new RaftException("format string has fewer verbs than arguments: " + format);
        }
        return buf.toString();
    }

    /**
     * 按单个动词渲染一个参数。
     */
    private static String render(char verb, Object arg) {
        switch (verb) {
            case 's':
                return formatString(arg);
            case 'd':
                return formatDecimal(arg);
            case 'x':
                return formatHex(arg);
            case 'v':
                return formatValue(arg);
            case 'q':
                return formatQuoted(arg);
            default:
                throw new RaftException("unsupported format verb %" + verb);
        }
    }

    /**
     * {@code %s}：字符串原样输出；{@code byte[]} 按原始字节输出。
     */
    private static String formatString(Object arg) {
        if (arg == null) {
            return "<nil>";
        }
        if (arg instanceof byte[]) {
            return new String((byte[]) arg, StandardCharsets.ISO_8859_1);
        }
        return arg.toString();
    }

    /**
     * {@code %d}：十进制整数。
     */
    private static String formatDecimal(Object arg) {
        if (arg == null) {
            return "<nil>";
        }
        if (arg instanceof Number) {
            Number n = (Number) arg;
            if (n instanceof Double || n instanceof Float) {
                throw new RaftException("cannot format a float with %d: " + arg);
            }
            return n.toString();
        }
        throw new RaftException("cannot format " + arg.getClass().getName() + " with %d");
    }

    /**
     * {@code %x}：十六进制。整数不带前缀也不补零。
     */
    private static String formatHex(Object arg) {
        if (arg == null) {
            return "<nil>";
        }
        if (arg instanceof byte[]) {
            byte[] bytes = (byte[]) arg;
            StringBuilder buf = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                buf.append(Character.forDigit((b >> 4) & 0xF, 16));
                buf.append(Character.forDigit(b & 0xF, 16));
            }
            return buf.toString();
        }
        if (arg instanceof Number) {
            Number n = (Number) arg;
            if (n instanceof Double || n instanceof Float) {
                throw new RaftException("cannot format a float with %x: " + arg);
            }
            return Long.toHexString(n.longValue());
        }
        return arg.toString();
    }

    /**
     * {@code %v}：默认格式，等价于对象的 {@code toString()}。
     */
    private static String formatValue(Object arg) {
        if (arg == null) {
            return "<nil>";
        }
        if (arg instanceof byte[]) {
            // Go 里 %v 作用在字节切片上得到的是十进制列表
            return goBytes((byte[]) arg);
        }
        return arg.toString();
    }

    /**
     * 把字节数组渲染成 Go 的 {@code %v} 形式，即十进制元素列表。
     *
     * <p>Go 里 {@code %v} 作用在 {@code []byte} 上得到的就是这种形式，
     * 例如 {@code []byte{1, 2}} 渲染成 {@code [1 2]}，空切片渲染成 {@code []}。</p>
     *
     * @param data 字节数组，可以为 null
     * @return 渲染结果，null 与空数组都得到 {@code []}
     */
    public static String goBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return "[]";
        }
        StringBuilder buf = new StringBuilder(data.length * 4);
        buf.append('[');
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                buf.append(' ');
            }
            buf.append(data[i]);
        }
        return buf.append(']').toString();
    }

    /**
     * {@code %q}：带双引号的字面量。
     */
    private static String formatQuoted(Object arg) {
        if (arg == null) {
            return "<nil>";
        }
        if (arg instanceof byte[]) {
            return RaftUtil.quote((byte[]) arg);
        }
        return RaftUtil.quote(arg.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把一组数字渲染成 Go 的切片格式，即 {@code [1 2 3]}。
     *
     * <p>Go 的 {@code %v} 作用在 {@code []uint64} 上得到的就是这种形式，
     * 元素之间用空格分隔。etcd 的 {@code DescribeConfState} 等地方依赖这个格式。</p>
     *
     * @param ids 节点 id 列表，可以为 null
     * @return 渲染结果，null 与空列表都得到 {@code []}
     */
    public static String goSlice(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        StringBuilder buf = new StringBuilder();
        buf.append('[');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                buf.append(' ');
            }
            buf.append(ids.get(i));
        }
        return buf.append(']').toString();
    }
}
