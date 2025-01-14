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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static io.edap.util.UnsafeUtil.copyMemory;

/**
 * 字符串常用的操作函数
 */
public class StringUtil {

    static Logger LOG = LoggerManager.getLogger(StringUtil.class);

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
                LOG.warn("", e);
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
                LOG.warn("", e);
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
            LOG.error("UnsafeUtil.getByte error", e);
        }
        return false;
    }

    /**
     * jdk9以上根据指定的byte数组和coder快速生成String的实例
     * @param data
     * @param coder
     * @return
     */
    public static String fastInstance(byte[] data, byte coder) throws InstantiationException {
        Object s = UnsafeUtil.allocateInstance(String.class);
        UnsafeUtil.putByte(s, CODER_FIELD_OFFSET, coder);
        UnsafeUtil.putObject(s, VALUE_FIELD_OFFSET, data);
        return (String)s;
    }

    /**
     * 判断字符串的对象是否为空，如果字符串时空指针或者字符串为空均为true
     * @param str 字符串对象
     * @return 是否为空null指针或者是空字符串则返回true
     */
    public static boolean isEmpty(String str) {
        return str==null || str.isEmpty();
    }


    public static void main(String[] args) {
        String s = "我们😁";
        Object v = UnsafeUtil.getValue(s, VALUE_FIELD_OFFSET);
        System.out.println(v);
        byte[] bs = new byte[8];
        copyMemory(s, VALUE_FIELD_OFFSET, bs, 0, 8);
        String d = new String(bs, StandardCharsets.UTF_16);
        System.out.println(d);
    }
}
