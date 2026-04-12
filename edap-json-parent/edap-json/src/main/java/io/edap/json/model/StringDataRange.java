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

package io.edap.json.model;

import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

public class StringDataRange implements DataRange<String> {

    private String value;
    private int start;
    private int end;
    private int hashCode;

    public StringDataRange() {}

    public static StringDataRange from(String str) {
        StringDataRange sdr = new StringDataRange();
        sdr.value(str);
        sdr.start(0);
        sdr.end(str.length());
        long hashCode = FNV_1a_INIT_VAL;
        for (int i=0;i<str.length();i++) {
            hashCode ^= str.charAt(i);
            hashCode *= FNV_1a_FACTOR_VAL;
        }
        sdr.hashCode = (int)hashCode;
        return sdr;
    }


    @Override
    public DataRange value(String value) {
        this.value = value;
        return this;
    }

    @Override
    public DataRange hashCode(final int hashCode) {
        this.hashCode = hashCode;
        return this;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public DataRange start(final int start) {
        this.start = start;
        return this;
    }

    @Override
    public DataRange end(final int end) {
        this.end = end;
        return this;
    }

    @Override
    public int keyHashCode(String key) {
        long hashCode = FNV_1a_INIT_VAL;
        for (int i=0;i<key.length();i++) {
            hashCode ^= key.charAt(i);
            hashCode *= FNV_1a_FACTOR_VAL;
        }
        return (int)hashCode;
    }

    @Override
    public boolean equals(Object dataRange) {
        if (this == dataRange) {
            return true;
        }
        if (dataRange instanceof StringDataRange) {
            StringDataRange other = (StringDataRange)dataRange;
            if (hashCode != other.hashCode) {
                return false;
            }
            if (end - start != other.end - other.start) {
                return false;
            }
            String _otherV = other.value;
            String _value = value;
            int otherIndex = other.start;
            for (int i=start;i<end;i++) {
                if (_value.charAt(i) != _otherV.charAt(otherIndex++)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
