/*
 * Copyright 2020 The edap Project
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

package io.edap.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static io.edap.util.UnsafeUtil.copyMemory;

/**
 * 字符串常用的操作函数
 */
public class StringUtil {

    /**
     * String中value是否是byte[]
     */
    public static final boolean IS_BYTE_ARRAY;
    /**
     * String中value的Field用来反射String的Value值
     */
    public static final Field VALUE_FIELD;

    public static final long VALUE_FIELD_OFFSET;

    public static final long CODER_FIELD_OFFSET;
    /**
     * String中coder的Field用来反射String的编码类型
     */
    public static final Field LATIN1_FIELD;
    /**
     * utf8编码的charset实例
     */
    public static final Charset UTF8_CHARSET = StandardCharsets.UTF_8;


    static {
        Field   valueField;
        Field   coderField = null;
        boolean isByteArray = false;
        try {
            valueField = String.class.getDeclaredField("value");
            valueField.setAccessible(true);
            isByteArray  = valueField.get("a").getClass().getName().equals("[B");
            if (isByteArray) {
                coderField = String.class.getDeclaredField("coder");
                coderField.setAccessible(true);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            valueField = null;
            coderField = null;
        }

        VALUE_FIELD   = valueField;
        IS_BYTE_ARRAY = isByteArray;
        LATIN1_FIELD  = coderField;
        VALUE_FIELD_OFFSET = UnsafeUtil.fieldOffset(VALUE_FIELD);
        CODER_FIELD_OFFSET = UnsafeUtil.fieldOffset(coderField);
    }

    private StringUtil() {}

    /**
     * jdk版本高于9时获取String中value的byte[]
     * @param s
     * @return
     */
    public static byte[] getValue(String s) {
        if (s == null) {
            return null;
        }
        if (IS_BYTE_ARRAY) {
            try {
                //return (byte[])VALUE_FIELD.get(s);
                return (byte[]) UnsafeUtil.getValue(s, VALUE_FIELD_OFFSET);
            } catch (Throwable e) {
                return s.getBytes(UTF8_CHARSET);
            }
        }
        return s.getBytes(UTF8_CHARSET);
    }

    public static char[] getCharValue(String s) {
        if (s == null) {
            return null;
        }
        if (!IS_BYTE_ARRAY) {
            try {
                //return (byte[])VALUE_FIELD.get(s);
                return (char[]) UnsafeUtil.getValue(s, VALUE_FIELD_OFFSET);
            } catch (Throwable e) {
                return s.toCharArray();
            }
        }
        return s.toCharArray();
    }

    /**
     * jdk版本高于jdk9时判断String是否是Latin1编码
     * @param s
     * @return
     */
    public static boolean isLatin1(String s) {
        try {
            //return LATIN1_FIELD.getByte(s) == 0;
            return UnsafeUtil.getByte(s, CODER_FIELD_OFFSET) == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * jdk9以上根据指定的byte数组和coder快速生成String的实例
     * @param data
     * @param coder
     * @return
     */
    public static String fastInstance(byte[] data, byte coder) {
        if (IS_BYTE_ARRAY) {
            try {
                Object s = UnsafeUtil.allocateInstance(String.class);
                UnsafeUtil.putByte(s, CODER_FIELD_OFFSET, coder);
                UnsafeUtil.putObject(s, VALUE_FIELD_OFFSET, data);
                return (String) s;
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            }
        } else {
            return new String(data);
        }
    }

    /**
     * 本机字节序是否是小端。
     *
     * <p>★ <b>JDK 的 String 内部 UTF-16 字节序 = 原生端序</b>：
     * {@code java.lang.StringUTF16} 的 {@code <clinit>} 调用 native 的 {@code isBigEndian()}
     * 决定 {@code HI_BYTE_SHIFT}/{@code LO_BYTE_SHIFT}
     * ⇒ 小端机上 String 的 {@code value} 是「低字节在前」，大端机上是「高字节在前」。</p>
     *
     * <p>⇒ 把 String 的内部 byte[] 原样搬到协议里时，<b>协议字节序 = 生产方机器的端序</b>，
     * 跨端序的机器直接还原会得到「每个字符高低字节互换」的乱码（且不报错）。</p>
     */
    public static final boolean NATIVE_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    /**
     * 翻转一段 UTF-16 数据的字节序（每 2 字节一组交换，即 BE ⇄ LE）。
     *
     * <p>用于「协议里固定一种字节序，落到本机 String 内部布局时按需转换」：
     * 大端机读到小端协议数据（或反之）才需要翻，<b>同端序时一次都不用翻</b>。</p>
     *
     * @param b   缓冲区
     * @param off 起始下标
     * @param len 字节数（奇数长度时最后一个字节不处理）
     */
    public static void swapUtf16ByteOrder(byte[] b, int off, int len) {
        for (int i = off, end = off + (len & ~1); i < end; i += 2) {
            byte t = b[i];
            b[i] = b[i + 1];
            b[i + 1] = t;
        }
    }

    /**
     * 判断字符串的对象是否为空，如果字符串时空指针或者字符串为空均为true
     * @param str 字符串对象
     * @return 是否为空null指针或者是空字符串则返回true
     */
    public static boolean isEmpty(String str) {
        return str==null || str.isEmpty();
    }

    /**
     * JDK 内部 {@code java.lang.StringCoding.hasNegatives(byte[],int,int)} 的 MethodHandle。
     *
     * <p>JDK 17 的 {@code String.getBytes(UTF_8)} 对 Latin1 串正是靠它判断「是不是纯 ASCII」：
     * <pre>{@code if (!hasNegatives(val,0,val.length)) return Arrays.copyOf(val, val.length);}</pre>
     * ⇒ 纯 ASCII 走整块 memcpy（<b>但必然分配一个新数组</b>）。</p>
     *
     * <p>★ 用 {@code MethodHandle.invokeExact} 调用时，C2 <b>照样</b>会把它替换成 intrinsic
     * （实测 {@code -XX:+PrintIntrinsics} 打出
     * {@code java.lang.StringCoding::hasNegatives (25 bytes) (intrinsic)}），
     * 所以这个「SIMD 判定」在 Java 侧<b>可用</b>。</p>
     *
     * <p>⚠ 需要 {@code --add-opens java.base/java.lang=ALL-UNNAMED}（edap-protobuf 本来就需要它）。
     * 拿不到（没加 flag / JDK 版本没有这个方法）时为 null ⇒ {@link #hasHighBytes} 保守返回 true
     * ⇒ 调用方走原来的 {@code getBytes(UTF_8)} 路径，<b>行为不变</b>。</p>
     */
    private static final MethodHandle HAS_NEGATIVES;

    static {
        MethodHandle mh = null;
        try {
            Method m = Class.forName("java.lang.StringCoding")
                    .getDeclaredMethod("hasNegatives", byte[].class, int.class, int.class);
            m.setAccessible(true);
            mh = MethodHandles.lookup().unreflect(m);
        } catch (Throwable t) {
            mh = null;
        }
        HAS_NEGATIVES = mh;
    }

    /**
     * 判断这段字节里有没有负数（= 有没有 ≥ 0x80 的字节）。
     *
     * <p>对 Latin1 编码的 String 而言：内部字节为负 ⇔ 字符 ≥ 0x80 ⇔ UTF-8 里要占 2 个字节；
     * 没有负数字节 ⇔ <b>纯 ASCII</b> ⇔ UTF-8 字节与内部字节<b>完全相同</b>。</p>
     *
     * <p>拿不到 JDK 的 SIMD intrinsic 时返回 {@code true}（保守 ⇒ 调用方走回退路径，行为不变）。</p>
     */
    public static boolean hasHighBytes(byte[] b, int off, int len) {
        if (HAS_NEGATIVES == null) {
            return true;
        }
        try {
            return (boolean) HAS_NEGATIVES.invokeExact(b, off, len);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 取 String 内部的 Latin1 {@code byte[]}（<b>只读</b>，与 String 共享，<b>不可修改</b>）。
     *
     * <p>用于「纯 ASCII 的字符串直接按 UTF-8 原样写出」——省掉
     * {@code getBytes(UTF_8)} 的一次分配 + 一次拷贝。</p>
     *
     * @param s 字符串
     * @return 内部字节数组；不是 Latin1 编码（或取不到）时返回 {@code null}
     */
    public static byte[] getLatin1Bytes(String s) {
        if (s == null || !IS_BYTE_ARRAY || !isLatin1(s)) {
            return null;
        }
        try {
            Object o = UnsafeUtil.getValue(s, VALUE_FIELD_OFFSET);
            return (o instanceof byte[]) ? (byte[]) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 由下划线分割的命名转换为驼峰命名
     * @return
     */
    public static String toCamelCase(String underScore) {
        int start = 0;
        int index = underScore.indexOf('_', start);
        StringBuilder name = new StringBuilder();
        while (index != -1) {
            if (index > start) {
                name.append(underScore.substring(start, start + 1).toUpperCase(Locale.ENGLISH))
                        .append(underScore.substring(start+1, index));
            }
            start = index + 1;
            index = underScore.indexOf('_', start);
        }
        name.append(underScore.substring(start, start + 1).toUpperCase(Locale.ENGLISH))
                .append(underScore.substring(start+1, index));
        return name.toString();
    }

    /**
     * 将驼峰命名的风格字符串转为下划线风格的命名字符串。连续多个大些字母时，如果以连续的大写字母
     * 结束则在第一个大写字母前增加下划线，如果不是以大写字母结束则在第一个大写字母前增加一个下划线
     * 然后再最后一个大写字母前增加一个下划线
     * @param camel
     * @return
     */
    public static String toUnderScore(String camel) {
        if (camel == null || camel.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int len = camel.length();
        for (int i=0;i<len;i++) {
            char c = camel.charAt(i);
            if (isUpperCase(c) && i>0) { //如果为大写字母则判断后面是否是大写字母
                int upCount = getUpperCaseCount(i, camel, len);
                if (upCount + i == len) {
                    if (i == len - 1) {
                        sb.append(toLowerCase(camel.charAt(i)));
                    } else {
                        sb.append("_").append(toLowerCase(c));
                        for (int j = 0; j < upCount - 1; j++) {
                            i++;
                            sb.append(toLowerCase(camel.charAt(i)));
                        }
                    }
                } else {
                    if (i > 0) {
                        sb.append("_");
                    }
                    sb.append(toLowerCase(c));
                    if (upCount > 1) {
                        for (int j=0;j<upCount-2;j++) {
                            i++;
                            sb.append(toLowerCase(camel.charAt(i)));
                        }
                    }
                }
            } else {
                if (isUpperCase(c)) {
                    sb.append(toLowerCase(c));
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 查询连续大写字母的个数
     * @param start 开始位置
     * @param camel 原字符串
     * @param len 字符传长度
     * @return
     */
    private static int getUpperCaseCount(int start, String camel, int len) {
        if (start == len -1) {
            return 1;
        }
        for (int i=start + 1;i<len;i++) {
            char c = camel.charAt(i);
            if (!isUpperCase(c)) {
                return i - start;
            }
        }
        return len - start;
    }

    public static char toLowerCase(char c) {
        return (char)(c + 32);
    }

    /**
     * 判断一个字符是否是大写字母
     * @param c
     * @return
     */
    public static boolean isUpperCase(char c) {
        return c >= 'A' && c <= 'Z';
    }
}
