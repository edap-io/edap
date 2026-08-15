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

import java.lang.reflect.Field;
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
     * 判断字符串的对象是否为空，如果字符串时空指针或者字符串为空均为true
     * @param str 字符串对象
     * @return 是否为空null指针或者是空字符串则返回true
     */
    public static boolean isEmpty(String str) {
        return str==null || str.isEmpty();
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
