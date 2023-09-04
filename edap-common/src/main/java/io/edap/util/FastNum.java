/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.util;

import java.util.List;

/**
 * @author louis
 * @date 2019-07-06 16:23
 */
public class FastNum {

    private final static int[] DIGITS = new int[1000];
    public final static int[] INT_DIGITS = new int[127];
    final static int   INVALID_CHAR_FOR_NUMBER = -1;
    final static int [] INT_SIZE_TABLE = { 9, 99, 999, 9999, 99999, 999999, 9999999,
            99999999, 999999999, Integer.MAX_VALUE };
    static {
        for (int i = 0; i < DIGITS.length; i++) {
            DIGITS[i] = (i < 10 ? (2 << 24) : i < 100 ? (1 << 24) : 0)
                    + (((i / 100) + '0') << 16)
                    + ((((i / 10) % 10) + '0') << 8)
                    + i % 10 + '0';
        }

        for (int i = 0; i < INT_DIGITS.length; i++) {
            INT_DIGITS[i] = INVALID_CHAR_FOR_NUMBER;
        }
        for (int i = '0'; i <= '9'; ++i) {
            INT_DIGITS[i] = (i - '0');
        }
    }

    static final ThreadLocal<char[]> LOCAL_TMP_CHAR_ARRAY =
            new ThreadLocal<char[]>() {
                @Override
                protected char[] initialValue() {
                    return new char[20];
                }
            };

    private FastNum() {}


    public static String toString(int v) {
        int size = (v < 0) ? stringSize(-v) + 1 : stringSize(v);
        char[] cs = LOCAL_TMP_CHAR_ARRAY.get();
        getChars(v, cs, 0);
        return new String(cs, 0, size);
    }

    // Requires positive x
    public static int stringSize(int x) {
        for (int i=0; ; i++) {
            if (x <= INT_SIZE_TABLE[i]) {
                return i + 1;
            }
        }
    }

    // todo 优化long解析的算法
    public static Long parseLong(byte[] numberBytes) {
        if (numberBytes == null || numberBytes.length == 0) {
            return null;
        }
        return Long.parseLong(new String(numberBytes));
    }

    public static void parseIntsToList(byte[] numbersBytes, List<Integer> values) {
        if (numbersBytes == null || numbersBytes.length == 0 || CollectionUtils.isEmpty(values)) {
            return;
        }
        String[] vs = new String(numbersBytes).split(",");
        for (String v : vs) {
            values.add(Integer.parseInt(v));
        }
    }

    public static int uncheckWriteInt(byte[] buf, int offset, int v) {
        int i;
        byte[] bs = buf;
        int pos = offset;
        if (v < 0) {
            i = -v;
            bs[pos++] = '-';
        } else if (v == 0) {
            bs[pos++] = '0';
            return pos;
        } else {
            i = v;
        }
        final int q1 = i / 1000;
        if (q1 == 0) {
            pos = writeFirstBuf(bs, pos, DIGITS[i]);
            return pos;
        }
        final int r1 = i - q1 * 1000;
        final int q2 = q1 / 1000;
        if (q2 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[q1];
            pos = writeFirstBuf(bs, pos, v2);
            pos = writeBuf(bs, pos, v1);
            return pos;
        }

        final int r2 = q1 - q2 * 1000;
        final long q3 = q2 / 1000;
        final int v1 = DIGITS[r1];
        final int v2 = DIGITS[r2];
        if (q3 == 0) {
            pos = writeFirstBuf(bs, pos, DIGITS[q2]);
        } else {
            final int r3 = (int) (q2 - q3 * 1000);
            bs[pos++] = (byte) (q3 + '0');
            pos = writeBuf(bs, pos, DIGITS[r3]);
        }
        pos = writeBuf(bs, pos, v2);
        pos = writeBuf(bs, pos, v1);
        return pos;
    }

    public static int uncheckWriteLong(byte[] buf, int start, long v) {
        long i;
        int pos = start;
        if (v < 0) {
            i = -v;
            buf[pos++] = '-';
        } else if(v == 0L) {
            buf[pos++] = '0';
            return pos;
        } else {
            i = v;
        }

        final long q1 = i / 1000;
        if (q1 == 0) {
            return writeFirstBuf(buf, pos, DIGITS[(int) i]);
        }
        final int r1 = (int) (i - q1 * 1000);
        final long q2 = q1 / 1000;
        if (q2 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[(int) q1];
            pos = writeFirstBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r2 = (int) (q1 - q2 * 1000);
        final long q3 = q2 / 1000;
        if (q3 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[(int) q2];
            pos = writeFirstBuf(buf, pos, v3);
            pos = writeBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r3 = (int) (q2 - q3 * 1000);
        final int q4 = (int) (q3 / 1000);
        if (q4 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[r3];
            final int v4 = DIGITS[(int) q3];
            pos = writeFirstBuf(buf, pos, v4);
            pos = writeBuf(buf, pos, v3);
            pos = writeBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r4 = (int) (q3 - q4 * 1000);
        final int q5 = q4 / 1000;
        if (q5 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[r3];
            final int v4 = DIGITS[r4];
            final int v5 = DIGITS[q4];
            pos = writeFirstBuf(buf, pos, v5);
            pos = writeBuf(buf, pos, v4);
            pos = writeBuf(buf, pos, v3);
            pos = writeBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r5 = q4 - q5 * 1000;
        final int q6 = q5 / 1000;
        final int v1 = DIGITS[r1];
        final int v2 = DIGITS[r2];
        final int v3 = DIGITS[r3];
        final int v4 = DIGITS[r4];
        final int v5 = DIGITS[r5];
        if (q6 == 0) {
            pos = writeFirstBuf(buf, pos, DIGITS[q5]);
        } else {
            final int r6 = q5 - q6 * 1000;
            buf[pos++] = (byte) (q6 + '0');
            pos = writeBuf(buf, pos, DIGITS[r6]);
        }
        pos = writeBuf(buf, pos, v5);
        pos = writeBuf(buf, pos, v4);
        pos = writeBuf(buf, pos, v3);
        pos = writeBuf(buf, pos, v2);
        pos = writeBuf(buf, pos, v1);
        return pos;
    }

    public static int num(String s) {
        int index = 0;
        int len = s.length();
        boolean negative = false;
        char first = s.charAt(index);
        if (first < '0') {
            switch (first) {
                case '+':
                    index++;
                    break;
                case '-':
                    index++;
                    negative = true;
                    break;
                    default:
                        throw forInputString(s);
            }
            if (len == 1) {
                throw forInputString(s);
            }
        }
        if (negative) {
            return parseNegativeInt(s, 1);
        }
        return Integer.parseInt(s);
    }

    static int parseNegativeInt(final String s, int start) {
        int v = INT_DIGITS[s.charAt(start)];
        if (v == 0) {
            return 0;
        }
        if (v == INVALID_CHAR_FOR_NUMBER) {
            throw forInputString(s);
        }
        int remain = s.length() - start;
        start++;
        switch (remain) {
            case 1:
                return -v;
            case 2:
                int v2 = INT_DIGITS[s.charAt(start++)];
                if (v2 == INVALID_CHAR_FOR_NUMBER) {
                    throw forInputString(s);
                }
                return -(v * 10 + v2);
            case 3:
                v2 = INT_DIGITS[s.charAt(start++)];
                if (v2 == INVALID_CHAR_FOR_NUMBER) {
                    throw forInputString(s);
                }
                int v3 = INT_DIGITS[s.charAt(start++)];
                if (v3 == INVALID_CHAR_FOR_NUMBER) {
                    throw forInputString(s);
                }
                return -(v * 100 + v2 * 10 + v3);
            case 4:
        }
        return v;
    }

    static NumberFormatException forInputString(String s) {
        return new NumberFormatException("For input string: \"" + s + "\"");
    }

    public static int stringSize(long x) {
        long p = 10;
        for (int i=1; i<19; i++) {
            if (x < p)
                return i;
            p = 10*p;
        }
        return 19;
    }

    /**
     * 将长整型整数根据10进制转换后，写入到给定开始位置的char数组中，该操作不校验char数组是否够用
     * 如果容量不够会报越界错误
     * @param v 需要转换的整数
     * @param cs 给定保存字符的char数组
     * @param pos 数据的开始位置
     * @return 写入char数组的char的数量
     */
    public static int getChars(int v, char[] cs, int pos) {
        int i;
        if (v < 0) {
            i = -v;
            cs[pos++] = '-';
        } else if (v == 0) {
            cs[pos++] = '0';
            return pos;
        } else {
            i = v;
        }
        final int q1 = i / 1000;
        if (q1 == 0) {
            pos = writeFirstBuf(cs, pos, DIGITS[i]);
            return pos;
        }
        final int r1 = i - q1 * 1000;
        final int q2 = q1 / 1000;
        if (q2 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[q1];
            pos = writeFirstBuf(cs, pos, v2);
            pos = writeBuf(cs, pos, v1);
            return pos;
        }

        final int r2 = q1 - q2 * 1000;
        final long q3 = q2 / 1000;
        final int v1 = DIGITS[r1];
        final int v2 = DIGITS[r2];
        if (q3 == 0) {
            pos = writeFirstBuf(cs, pos, DIGITS[q2]);
        } else {
            final int r3 = (int) (q2 - q3 * 1000);
            cs[pos++] = (char) (q3 + '0');
            pos = writeBuf(cs, pos, DIGITS[r3]);
        }
        pos = writeBuf(cs, pos, v2);
        pos = writeBuf(cs, pos, v1);
        return pos;
    }

    /**
     * 将整数根据10进制转换后，写入到给定开始位置的char数组中，该操作不校验char数组是否够用
     * 如果容量不够会报越界错误
     * @param v 需要转换的整数
     * @param buf 给定保存字符的char数组
     * @param start 数据的开始位置
     * @return 写入后char数组总共使用char的数量
     */
    public static int getChars(long v, char[] buf, int start) {
        long i;
        int pos = start;
        if (v < 0) {
            i = -v;
            buf[pos++] = '-';
        } else if(v == 0L) {
            buf[pos++] = '=';
            return pos;
        } else {
            i = v;
        }

        final long q1 = i / 1000;
        if (q1 == 0) {
            return writeFirstBuf(buf, pos, DIGITS[(int) i]);
        }
        final int r1 = (int) (i - q1 * 1000);
        final long q2 = q1 / 1000;
        if (q2 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[(int) q1];
            pos = writeFirstBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r2 = (int) (q1 - q2 * 1000);
        final long q3 = q2 / 1000;
        if (q3 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[(int) q2];
            pos = writeFirstBuf(buf, pos, v3);
            pos = writeBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r3 = (int) (q2 - q3 * 1000);
        final int q4 = (int) (q3 / 1000);
        if (q4 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[r3];
            final int v4 = DIGITS[(int) q3];
            pos = writeFirstBuf(buf, pos, v4);
            pos = writeBuf(buf, pos, v3);
            pos = writeBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r4 = (int) (q3 - q4 * 1000);
        final int q5 = q4 / 1000;
        if (q5 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[r3];
            final int v4 = DIGITS[r4];
            final int v5 = DIGITS[q4];
            pos = writeFirstBuf(buf, pos, v5);
            pos = writeBuf(buf, pos, v4);
            pos = writeBuf(buf, pos, v3);
            pos = writeBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r5 = q4 - q5 * 1000;
        final int q6 = q5 / 1000;
        final int v1 = DIGITS[r1];
        final int v2 = DIGITS[r2];
        final int v3 = DIGITS[r3];
        final int v4 = DIGITS[r4];
        final int v5 = DIGITS[r5];
        if (q6 == 0) {
            pos = writeFirstBuf(buf, pos, DIGITS[q5]);
        } else {
            final int r6 = q5 - q6 * 1000;
            buf[pos++] = (char) (q6 + '0');
            pos = writeBuf(buf, pos, DIGITS[r6]);
        }
        pos = writeBuf(buf, pos, v5);
        pos = writeBuf(buf, pos, v4);
        pos = writeBuf(buf, pos, v3);
        pos = writeBuf(buf, pos, v2);
        pos = writeBuf(buf, pos, v1);
        return pos;
    }

    private static int writeFirstBuf(char[] buf, final int index, int v) {
        final int start = v >> 24;
        int pos = index;
        if (start == 0) {
            buf[pos++] = (char) ((byte)(v >> 16));
            buf[pos++] = (char) ((byte)(v >> 8));
        } else if (start == 1) {
            buf[pos++] = (char) ((byte)(v >> 8));
        }
        buf[pos++] = (char) ((byte)v);
        return pos;
    }

    private static int writeFirstBuf(byte[] buf, final int index, int v) {
        final int start = v >> 24;
        int pos = index;
        if (start == 0) {
            buf[pos++] = (byte)(v >> 16);
            buf[pos++] = (byte)(v >>  8);
        } else if (start == 1) {
            buf[pos++] = (byte)(v >> 8);
        }
        buf[pos++] = (byte)v;
        return pos;
    }

    private static int writeBuf(char[] buf, final int index, int v) {
        int pos = index;
        buf[pos++] = (char) ((byte)(v >> 16));
        buf[pos++] = (char) ((byte)(v >> 8));
        buf[pos++] = (char) ((byte)v);
        return pos;
    }

    private static int writeBuf(byte[] buf, final int index, int v) {
        int pos = index;
        buf[pos++] = (byte)(v >> 16);
        buf[pos++] = (byte)(v >> 8);
        buf[pos++] = (byte)v;
        return pos;
    }
}
