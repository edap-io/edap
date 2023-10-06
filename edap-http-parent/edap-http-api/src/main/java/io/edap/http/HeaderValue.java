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

public class HeaderValue {
    private String value;
    private byte[] data;
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
        if (value == null && data != null) {
            value = new String(data);
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
        if (intValue == null && data != null) {
            int v;
            int count = 0;
            for (int j=data.length-1;j>=0;j--) {
                if (data[j] == ' ') {
                    count++;
                } else {
                    break;
                }
            }
            int len = data.length - count;
            switch (len) {
                case 1:
                    v = INT_DIGITS[data[0]];
                    break;
                case 2:
                    v = INT_DIGITS[data[0]] * 10 + INT_DIGITS[data[1]];
                    break;
                case 3:
                    v = INT_DIGITS[data[0]] * 100 + INT_DIGITS[data[1]] * 10
                            + INT_DIGITS[data[2]];
                    break;
                case 4:
                    v = INT_DIGITS[data[0]] * 1000 + INT_DIGITS[data[1]] * 100
                            + INT_DIGITS[data[2]] * 10 + INT_DIGITS[data[3]];
                    break;
                case 5:
                    v = INT_DIGITS[data[0]] * 10000 + INT_DIGITS[data[1]] * 1000
                            + INT_DIGITS[data[2]] * 100 + INT_DIGITS[data[3]] * 10
                            + INT_DIGITS[data[4]];
                    break;
                case 6:
                    v = INT_DIGITS[data[0]] * 100000 + INT_DIGITS[data[1]] * 10000
                            + INT_DIGITS[data[2]] * 1000 + INT_DIGITS[data[3]] * 100
                            + INT_DIGITS[data[4]] * 10 + INT_DIGITS[data[5]];
                    break;
                case 7:
                    v = INT_DIGITS[data[0]] * 1000000 + INT_DIGITS[data[1]] * 100000
                            + INT_DIGITS[data[2]] * 10000 + INT_DIGITS[data[3]] * 1000
                            + INT_DIGITS[data[4]] * 100 + INT_DIGITS[data[5]] * 10
                            + INT_DIGITS[data[6]];
                    break;
                case 8:
                    v = INT_DIGITS[data[0]] * 10000000 + INT_DIGITS[data[1]] * 1000000
                            + INT_DIGITS[data[2]] * 100000 + INT_DIGITS[data[3]] * 10000
                            + INT_DIGITS[data[4]] * 1000 + INT_DIGITS[data[5]] * 100
                            + INT_DIGITS[data[6]] * 10 + INT_DIGITS[data[7]];
                     break;
                case 9:
                    v = INT_DIGITS[data[0]] * 100000000 + INT_DIGITS[data[1]] * 10000000
                            + INT_DIGITS[data[2]] * 1000000 + INT_DIGITS[data[3]] * 100000
                            + INT_DIGITS[data[4]] * 10000 + INT_DIGITS[data[5]] * 1000
                            + INT_DIGITS[data[6]] * 100 + INT_DIGITS[data[7]] * 10
                            + INT_DIGITS[data[8]];
                    break;
                case 10:
                    v = INT_DIGITS[data[0]] * 1000000000 + INT_DIGITS[data[1]] * 100000000
                            + INT_DIGITS[data[2]] * 10000000 + INT_DIGITS[data[3]] * 1000000
                            + INT_DIGITS[data[4]] * 100000 + INT_DIGITS[data[5]] * 10000
                            + INT_DIGITS[data[6]] * 1000 + INT_DIGITS[data[7]] * 100
                            + INT_DIGITS[data[8]] * 10 + INT_DIGITS[data[9]];
                    break;
                default:
                    v = -1;

            }
            if (v != -1) {
                intValue = v;
            }
        }

        return intValue.intValue();
    }
}
