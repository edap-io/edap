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

package io.edap.codec;

import io.edap.util.StringUtil;

import java.nio.charset.Charset;

import static io.edap.util.Constants.*;

public class BytesDataRange implements DataRange<Integer, byte[]> {

    byte[] buf;

    boolean urlEncoded;

    boolean strict;

    byte first;

    byte last;

    int length;

    int start;

    int hash;

    public static BytesDataRange from(String v) {
        if (StringUtil.isEmpty(v)) {
            return null;
        }
        BytesDataRange dr = new BytesDataRange();
        byte[] bytes = v.getBytes(DEFAULT_CHARSET);
        int length = bytes.length;
        long hashCode = FNV_1a_INIT_VAL;
        byte[] buf = new byte[length];
        System.arraycopy(bytes, 0, buf, 0, length);
        dr.start = 0;
        dr.first = bytes[0];
        dr.last  = bytes[bytes.length-1];
        dr.buf = buf;
        for (byte aByte : bytes) {
            hashCode ^= aByte;
            hashCode *= FNV_1a_FACTOR_VAL;
        }
        dr.length = bytes.length;
        dr.hash = (int)hashCode;
        return dr;
    }

    @Override
    public boolean matchStrict() {
        return strict;
    }

    @Override
    public DataRange<Integer, byte[]> matchStrict(boolean strict) {
        this.strict = strict;
        return this;
    }

    @Override
    public byte first() {
        return first;
    }

    @Override
    public DataRange<Integer, byte[]> first(byte first) {
        this.first = first;
        return this;
    }

    @Override
    public byte last() {
        return last;
    }

    @Override
    public DataRange<Integer, byte[]> last(byte last) {
        this.last = last;
        return this;
    }

    @Override
    public byte[] buffer() {
        return buf;
    }

    @Override
    public DataRange<Integer, byte[]> buffer(byte[] buffer) {
        this.buf = buffer;
        return this;
    }

    @Override
    public Integer start() {
        return start;
    }

    @Override
    public DataRange<Integer, byte[]> start(Integer start) {
        this.start = start;
        return this;
    }

    @Override
    public DataRange<Integer, byte[]> hashCode(int hash) {
        this.hash = hash;
        return this;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public DataRange<Integer, byte[]> length(int length) {
        this.length = length;
        return this;
    }

    @Override
    public String getString(Charset charset) {
        return new String(buf, start, length, charset);
    }

    @Override
    public void reset() {

    }
}
