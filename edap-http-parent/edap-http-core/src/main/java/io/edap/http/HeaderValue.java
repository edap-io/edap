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

package io.edap.http;

import static io.edap.util.FastNum.INT_DIGITS;
import static io.edap.util.StringUtil.fastInstance;

public class HeaderValue {
    private String  value;
    private byte[]  data;
    private Integer intValue;

    public HeaderValue() {

    }

    public HeaderValue(String value) {
        this.value = value;
        this.data  = value.getBytes();
    }

    public HeaderValue(byte[] data) {
        this.data = data;
    }

    public String getValue() {
        if (value != null) {
            return value;
        }
        if (data != null) {
            value = fastInstance(data, (byte)0);
            return value;
        }
        return null;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public int getIntValue() {
        if (intValue != null) {
            return intValue.intValue();
        }
        if (data != null) {
            int v;
            int count = 0;
            for (int j=0;j<data.length;j++) {
                if (data[j] == ' ') {
                    count++;
                } else {
                    break;
                }
            }
            int len = data.length - count;
            switch (len) {
                case 1:
                    v = INT_DIGITS[data[count]];
                    break;
                case 2:
                    v = INT_DIGITS[data[count++]] * 10 + INT_DIGITS[data[count]];
                    break;
                case 3:
                    v = INT_DIGITS[data[count++]] * 100 + INT_DIGITS[data[count++]] * 10
                            + INT_DIGITS[data[count]];
                    break;
                case 4:
                    v = INT_DIGITS[data[count++]] * 1000 + INT_DIGITS[data[count++]] * 100
                            + INT_DIGITS[data[count++]] * 10 + INT_DIGITS[data[count]];
                    break;
                case 5:
                    v = INT_DIGITS[data[count++]] * 10000 + INT_DIGITS[data[count++]] * 1000
                            + INT_DIGITS[data[count++]] * 100 + INT_DIGITS[data[count++]] * 10
                            + INT_DIGITS[data[count]];
                    break;
                case 6:
                    v = INT_DIGITS[data[count++]] * 100000 + INT_DIGITS[data[count++]] * 10000
                            + INT_DIGITS[data[count++]] * 1000 + INT_DIGITS[data[count++]] * 100
                            + INT_DIGITS[data[count++]] * 10 + INT_DIGITS[data[count]];
                    break;
                case 7:
                    v = INT_DIGITS[data[count++]] * 1000000 + INT_DIGITS[data[count++]] * 100000
                            + INT_DIGITS[data[count++]] * 10000 + INT_DIGITS[data[count++]] * 1000
                            + INT_DIGITS[data[count++]] * 100 + INT_DIGITS[data[count++]] * 10
                            + INT_DIGITS[data[count]];
                    break;
                case 8:
                    v = INT_DIGITS[data[count++]] * 10000000 + INT_DIGITS[data[count++]] * 1000000
                            + INT_DIGITS[data[count++]] * 100000 + INT_DIGITS[data[count++]] * 10000
                            + INT_DIGITS[data[count++]] * 1000 + INT_DIGITS[data[count++]] * 100
                            + INT_DIGITS[data[count++]] * 10 + INT_DIGITS[data[count]];
                     break;
                case 9:
                    v = INT_DIGITS[data[count++]] * 100000000 + INT_DIGITS[data[count++]] * 10000000
                            + INT_DIGITS[data[count++]] * 1000000 + INT_DIGITS[data[count++]] * 100000
                            + INT_DIGITS[data[count++]] * 10000 + INT_DIGITS[data[count++]] * 1000
                            + INT_DIGITS[data[count++]] * 100 + INT_DIGITS[data[count++]] * 10
                            + INT_DIGITS[data[count]];
                    break;
                case 10:
                    v = INT_DIGITS[data[count++]] * 1000000000 + INT_DIGITS[data[count++]] * 100000000
                            + INT_DIGITS[data[count++]] * 10000000 + INT_DIGITS[data[count++]] * 1000000
                            + INT_DIGITS[data[count++]] * 100000 + INT_DIGITS[data[count++]] * 10000
                            + INT_DIGITS[data[count++]] * 1000 + INT_DIGITS[data[count++]] * 100
                            + INT_DIGITS[data[count++]] * 10 + INT_DIGITS[data[count]];
                    break;
                default:
                    v = -1;

            }
            if (v != -1) {
                intValue = v;
            }
        }

        return intValue==null?-1:intValue;
    }
}
