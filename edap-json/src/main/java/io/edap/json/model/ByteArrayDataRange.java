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

import java.nio.charset.StandardCharsets;

import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

public class ByteArrayDataRange implements DataRange<byte[]> {

    private byte[] value;
    private int start;
    private int end;
    private int hashCode;

    public ByteArrayDataRange() {

    }

    public ByteArrayDataRange(byte[] value, int start, int end, int hashCode) {
        this.value = value;
        this.start = start;
        this.end   = end;
        this.hashCode = hashCode;
    }

    public void fill(byte[] value, int start, int end, int hashCode) {
        this.value = value;
        this.start = start;
        this.end   = end;
        this.hashCode = hashCode;
    }

    @Override
    public DataRange value(byte[] value) {
        this.value = value;
        return this;
    }

    public static ByteArrayDataRange from(String name) {
        ByteArrayDataRange sdr = new ByteArrayDataRange();
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        sdr.value(nameBytes);
        sdr.start(0);
        sdr.end(nameBytes.length);
        long hashCode = FNV_1a_INIT_VAL;
        for (int i=0;i<nameBytes.length;i++) {
            hashCode ^= nameBytes[i];
            hashCode *= FNV_1a_FACTOR_VAL;
        }
        sdr.hashCode = (int)hashCode;
        return sdr;
    }

    @Override
    public DataRange hashCode(int hashCode) {
        this.hashCode = hashCode;
        return this;
    }

    public int end() {
        return end;
    }

    public int start() {
        return start;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public DataRange start(int start) {
        this.start = start;
        return this;
    }

    @Override
    public DataRange end(int end) {
        this.end = end;
        return this;
    }

    @Override
    public int keyHashCode(String key) {
        long hashCode = FNV_1a_INIT_VAL;
        byte[] bs = key.getBytes(StandardCharsets.UTF_8);
        for (int i=0;i<bs.length;i++) {
            hashCode ^= bs[i];
            hashCode *= FNV_1a_FACTOR_VAL;
        }
        return (int)hashCode;
    }

    @Override
    public boolean equals(Object dataRange) {
        if (this == dataRange) {
            return true;
        }
        if (dataRange instanceof ByteArrayDataRange) {
            ByteArrayDataRange other = (ByteArrayDataRange)dataRange;
            if (hashCode != other.hashCode) {
                return false;
            }
            if (end - start != other.end - other.start) {
                return false;
            }
            byte[] _otherV = other.value;
            byte[] _value = value;
            int otherIndex = other.start;
            for (int i=start;i<end;i++) {
                if (_value[i] != _otherV[otherIndex++]) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
