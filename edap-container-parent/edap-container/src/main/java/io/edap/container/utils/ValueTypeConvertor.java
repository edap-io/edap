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

package io.edap.container.utils;

import io.edap.json.JsonParseException;
import io.edap.util.StringUtil;

import java.math.BigDecimal;

public class ValueTypeConvertor {

    public final static int INVALID_CHAR_FOR_NUMBER = -1;

    public static int[] INT_DIGITS = new int[128];

    static {
        // 标识符首字母允许的符号
        for (int i=0;i<128;i++) {
            INT_DIGITS[i] = INVALID_CHAR_FOR_NUMBER;
        }

        for (int i='0';i<='9';i++) {
            INT_DIGITS[i] = i - '0';
        }
    }

    private ValueTypeConvertor() {}

    public static Boolean convertToBooleanObj(String value) {
        if (StringUtil.isEmpty(value)) {
            return null;
        }
        try {
            return Boolean.valueOf(value);
        } catch (NumberFormatException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean convertToBoolean(String value) {
        if (StringUtil.isEmpty(value)) {
            return false;
        }
        try {
            return Boolean.valueOf(value);
        } catch (NumberFormatException e) {
            throw new RuntimeException(e);
        }
    }

    public static BigDecimal convertToBigDecimal(String value) {
        if (StringUtil.isEmpty(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return BigDecimal.valueOf(Double.valueOf(value));
        } catch (NumberFormatException e) {
            throw new RuntimeException(e);
        }
    }

    public static float convertToShort(String value) {
        if (StringUtil.isEmpty(value)) {
            return 0;
        }
        try {
            return Short.parseShort(value);
        } catch (NumberFormatException e) {
            throw new RuntimeException(e);
        }
    }

    public static int convertToInt(String value) {
        if (StringUtil.isEmpty(value)) {
            return 0;
        }
        return convertInt(value);
    }

    public static Integer convertToInteger(String value) {
        if (StringUtil.isEmpty(value)) {
            return null;
        }
        return convertInt(value);
    }

    public static float convertToFloat(String value) {
        if (StringUtil.isEmpty(value)) {
            return 0;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new RuntimeException(e);
        }
    }

    public static Double convertToDoubleObj(String value) {
        if (StringUtil.isEmpty(value)) {
            return null;
        }
        return convertToDoubleObj(value);
    }

    public static double convertToDouble(String value) {
        if (StringUtil.isEmpty(value)) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new RuntimeException(e);
        }
    }

    public static Long convertToLongObj(String value) {
        return convertToLong(value);
    }

    public static long convertToLong(String value) {
        if (StringUtil.isEmpty(value)) {
            return 0;
        }
        int len = value.length();
        long v = 0;
        if (value.charAt(0) == '-') {
            if (len > 20) {
                throw new RuntimeException(value + " is too large for long");
            }
            for (int i=1;i<len;i++) {
                int ind = INT_DIGITS[value.charAt(i)];
                if (ind == INVALID_CHAR_FOR_NUMBER) {
                    throw new JsonParseException("整数不符合规范");
                }
                if (i == 1) {
                    v = ind;
                } else {
                    v = (v << 3) + (v << 1) + ind;
                }
            }

            return -v;
        } else {
            if (len > 19) {
                throw new RuntimeException(value + " is too large for long");
            }
            for (int i=0;i<len;i++) {
                int ind = INT_DIGITS[value.charAt(i)];
                if (ind == INVALID_CHAR_FOR_NUMBER) {
                    throw new JsonParseException("整数不符合规范");
                }
                if (i == 0) {
                    v = ind;
                } else {
                    v = (v << 3) + (v << 1) + ind;
                }
            }
            return v;
        }
    }

    private static int convertInt(String value) {
        int len = value.length();
        int v = 0;
        if (value.charAt(0) == '-') {
            if (len > 11) {
                throw new RuntimeException(value + " is too large for int");
            }
            for (int i=1;i<len;i++) {
                int ind = INT_DIGITS[value.charAt(i)];
                if (ind == INVALID_CHAR_FOR_NUMBER) {
                    throw new JsonParseException("整数不符合规范");
                }
                if (i == 1) {
                    v = ind;
                } else {
                    v = (v << 3) + (v << 1) + ind;
                }
            }

            return -v;
        } else {
            if (len > 10) {
                throw new RuntimeException(value + " is too large for int");
            }
            for (int i=0;i<len;i++) {
                int ind = INT_DIGITS[value.charAt(i)];
                if (ind == INVALID_CHAR_FOR_NUMBER) {
                    throw new JsonParseException("整数不符合规范");
                }
                if (i == 0) {
                    v = ind;
                } else {
                    v = (v << 3) + (v << 1) + ind;
                }
            }
            return v;
        }
    }
}
